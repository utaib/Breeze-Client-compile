package dev.breeze.recorder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The screen video's encoder while recording: ffmpeg fed NV12 frames (the
 * graphics card converts them, half the bytes of RGBA) on its standard
 * input, into a fragmented MP4, which stays playable up to its last
 * fragment if the game or the computer stops mid-recording.
 *
 * The game never waits for it. A frame is offered with {@link #offer}; when
 * the queue is full (ffmpeg is behind) the offer fails at once and the
 * caller drops the frame, so the slot shows the frame before it again.
 * Frames arrive as {@link Source}s: memory the graphics card wrote (a
 * mapped pixel buffer), copied out on this class's own thread, so not even
 * the copy is on the render thread. A few frame buffers are used, whatever the
 * recording's length.
 */
public final class LiveVideo implements AutoCloseable {

    /** A frame's bytes where the graphics card left them. */
    public interface Source {
        /** Copies the frame's bytes into dst (any thread). */
        void copyTo(byte[] dst);
        /** The bytes are no longer needed (called once, after copyTo or instead of it). */
        void release();
    }

    /** How many frames may wait to be copied out before new ones are dropped. */
    public static final int QUEUE = 4;
    /** Frame buffers: those waiting for ffmpeg and the one it repeats. */
    static final int BUFFERS = QUEUE + 2;

    private record Item(Source source, int repeatBefore) {}
    private record Out(byte[] frame, int repeatBefore) {}
    private static final Item END = new Item(null, 0);
    private static final Out OUT_END = new Out(null, -1);

    private final Process process;
    private final BlockingQueue<Item> copyQueue = new ArrayBlockingQueue<>(QUEUE);
    private final BlockingQueue<Out> writeQueue = new java.util.concurrent.LinkedBlockingQueue<>();
    private final BlockingQueue<byte[]> free = new ArrayBlockingQueue<>(BUFFERS);
    private final Thread copier, writer;
    private final StringBuilder errors = new StringBuilder();
    private final int frameBytes;
    private final AtomicInteger owed = new AtomicInteger();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong refused = new AtomicLong();
    private volatile String failure;
    private volatile long writeNanos, copyNanos, copies;

    public LiveVideo(List<String> command, int frameBytes) throws IOException {
        this.frameBytes = frameBytes;
        for (int i = 0; i < BUFFERS; i++) free.add(new byte[frameBytes]);
        process = new ProcessBuilder(command).start();
        Thread err = new Thread(() -> {
            try (InputStream in = process.getErrorStream()) {
                byte[] b = in.readAllBytes();
                synchronized (errors) {
                    errors.append(new String(b, StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {}
        }, "Breeze screen video errors");
        err.setDaemon(true);
        err.start();
        Thread out = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                in.transferTo(OutputStream.nullOutputStream());
            } catch (IOException ignored) {}
        }, "Breeze screen video output");
        out.setDaemon(true);
        out.start();
        copier = new Thread(this::copy, "Breeze screen video copy");
        copier.setDaemon(true);
        copier.start();
        writer = new Thread(this::write, "Breeze screen video writer");
        writer.setDaemon(true);
        writer.setPriority(Thread.NORM_PRIORITY - 1);
        writer.start();
    }

    /**
     * Hands a frame over, after repeatBefore repeats of the frame before.
     * Never waits: false when frames are already waiting to be copied or
     * ffmpeg has failed, and then the caller still owns the source.
     */
    public boolean offer(Source s, int repeatBefore) {
        if (failure != null) return false;
        boolean ok = copyQueue.offer(new Item(s, Math.max(0, repeatBefore)));
        if (!ok) refused.incrementAndGet();
        return ok;
    }

    /** The frame before is shown n more times (a frame that was planned but could not be read back). */
    public void repeat(int n) {
        if (n > 0) owed.addAndGet(n);
    }

    /** Video frames ffmpeg has taken (repeats included). */
    public long framesWritten() {
        return written.get();
    }

    /** Frames refused because the copy or ffmpeg was behind. */
    public long refused() {
        return refused.get();
    }

    /** Average time ffmpeg took to take one frame, ms (how close the encoder is to falling behind). */
    public double writeMs() {
        long n = written.get();
        return n == 0 ? 0 : writeNanos / 1e6 / n;
    }

    /** Average time to copy one frame out of the graphics card's buffer, ms (off the render thread). */
    public double copyMs() {
        return copies == 0 ? 0 : copyNanos / 1e6 / copies;
    }

    public String failure() {
        return failure;
    }

    /** Copies each frame out of the graphics card's memory at once, so its buffer goes back within a millisecond or so. */
    private void copy() {
        try {
            while (true) {
                Item it = copyQueue.take();
                if (it == END) {
                    writeQueue.put(OUT_END);
                    return;
                }
                byte[] buf = failure == null ? free.poll() : null;
                try {
                    if (buf != null) {
                        long t0 = System.nanoTime();
                        it.source().copyTo(buf);
                        copyNanos += System.nanoTime() - t0;
                        copies++;
                    }
                } finally {
                    it.source().release();
                }
                if (buf == null) {
                    // ffmpeg is behind by every buffer: this slot repeats the frame before.
                    refused.incrementAndGet();
                    owed.addAndGet(it.repeatBefore() + 1);
                } else {
                    writeQueue.put(new Out(buf, it.repeatBefore()));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void write() {
        byte[] last = null;
        try (OutputStream o = process.getOutputStream()) {
            while (true) {
                Out it = writeQueue.take();
                int n = (it == OUT_END ? 0 : it.repeatBefore()) + owed.getAndSet(0);
                if (last != null) {
                    for (int i = 0; i < n; i++) {
                        o.write(last);
                        written.incrementAndGet();
                    }
                }
                if (it == OUT_END) break;
                long t0 = System.nanoTime();
                o.write(it.frame());
                writeNanos += System.nanoTime() - t0;
                written.incrementAndGet();
                if (last != null) free.offer(last);
                last = it.frame();
            }
        } catch (Throwable t) {
            failure = "ffmpeg stopped taking frames";
            Item it;
            while ((it = copyQueue.poll()) != null) if (it.source() != null) it.source().release();
        }
    }

    /**
     * Ends the video after trailing repeats of the last frame and waits for
     * ffmpeg (on the caller's thread; never the render thread). Null when it
     * worked, else what went wrong.
     */
    public String finish(int trailing) {
        try {
            repeat(trailing);
            copyQueue.put(END);
            copier.join(30_000);
            writer.join(60_000);
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "ffmpeg did not finish";
            }
            if (process.exitValue() != 0) {
                synchronized (errors) {
                    String e = errors.toString().trim();
                    return "ffmpeg failed" + (e.isEmpty() ? "" : ": " + e.lines().reduce((x, y) -> y).orElse(""));
                }
            }
            return failure;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "the recording was interrupted";
        }
    }

    /** Stops at once (sources still waiting are released). */
    @Override
    public void close() {
        failure = failure == null ? "stopped" : failure;
        Item it;
        while ((it = copyQueue.poll()) != null) if (it.source() != null) it.source().release();
        copyQueue.offer(END);
        process.destroy();
    }

    int frameBytes() {
        return frameBytes;
    }

    // ── The command ────────────────────────────────────────────────────────

    /**
     * The encoder's options while recording live: each is set to keep up
     * with the game in real time (x264 at a fast preset; the graphics card
     * encoders at their balanced settings), taking NV12 as it comes.
     */
    public static List<String> liveCodecArgs(VideoEncoder.Codec c, VideoEncoder.Quality q) {
        int crf = switch (q) { case STANDARD -> 23; case HIGH -> 19; case BEST -> 16; };
        List<String> a = new ArrayList<>();
        switch (c.name()) {
            case "libx264" -> a.addAll(List.of("-c:v", "libx264", "-preset", q == VideoEncoder.Quality.BEST ? "faster" : "veryfast",
                    "-crf", String.valueOf(crf), "-pix_fmt", "yuv420p"));
            case "h264_nvenc" -> a.addAll(List.of("-c:v", "h264_nvenc", "-preset", "p4", "-rc", "vbr", "-cq", String.valueOf(crf + 1),
                    "-b:v", "0", "-pix_fmt", "nv12"));
            case "h264_amf" -> a.addAll(List.of("-c:v", "h264_amf", "-quality", "balanced", "-rc", "cqp",
                    "-qp_i", String.valueOf(crf), "-qp_p", String.valueOf(crf + 2), "-pix_fmt", "nv12"));
            case "h264_qsv" -> a.addAll(List.of("-c:v", "h264_qsv", "-preset", "faster", "-global_quality", String.valueOf(crf + 1),
                    "-pix_fmt", "nv12"));
            case "h264_videotoolbox" -> {
                int qv = switch (q) { case STANDARD -> 60; case HIGH -> 70; case BEST -> 80; };
                a.addAll(List.of("-c:v", "h264_videotoolbox", "-realtime", "1", "-q:v", String.valueOf(qv), "-pix_fmt", "nv12"));
            }
            case "h264_vaapi" -> a.addAll(List.of("-c:v", "h264_vaapi", "-qp", String.valueOf(crf)));
            default -> a.addAll(List.of("-c:v", c.name()));
        }
        return a;
    }

    /**
     * The whole ffmpeg command: NV12 frames of w x h (top row first) at fps
     * on standard input, BT.709 limited range as the graphics card converts
     * them, a key frame every two seconds, into a fragmented MP4.
     */
    public static List<String> command(String ffmpeg, VideoEncoder.Codec c, VideoEncoder.Quality q, int w, int h, int fps, Path out) {
        List<String> a = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-y"));
        if ("h264_vaapi".equals(c.name())) a.addAll(List.of("-vaapi_device", "/dev/dri/renderD128"));
        a.addAll(List.of("-f", "rawvideo", "-pix_fmt", "nv12", "-s", w + "x" + h, "-framerate", String.valueOf(fps), "-i", "-"));
        if ("h264_vaapi".equals(c.name())) a.addAll(List.of("-vf", "format=nv12,hwupload"));
        a.addAll(liveCodecArgs(c, q));
        a.addAll(VideoEncoder.levelArgs(c, VideoEncoder.level(w, h, fps)));
        a.addAll(List.of("-g", String.valueOf(fps * 2),
                "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709", "-color_range", "tv",
                "-movflags", "+frag_keyframe+empty_moov+default_base_moof",
                "-an", out.toAbsolutePath().toString()));
        return a;
    }

    /** Bytes in one NV12 frame of w by h (both even): the full-size Y plane and the half-size interleaved U and V. */
    public static int nv12Bytes(int w, int h) {
        return w * h * 3 / 2;
    }
}
