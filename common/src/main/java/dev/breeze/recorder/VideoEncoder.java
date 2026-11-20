package dev.breeze.recorder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The export's video encoder: ffmpeg, run as its own program (downloaded by
 * the launcher for the player's system), fed raw frames on its standard
 * input. Frames go through a small queue to a writer thread, so the game
 * thread only hands over a buffer; with {@link #QUEUE} frames in flight the
 * memory used is a few frames, whatever the video's length.
 *
 * The graphics card's own encoder is used when ffmpeg has one that works on
 * this computer (NVENC, AMF, Quick Sync, VideoToolbox, VAAPI); otherwise
 * x264 on the processor. Each is tried with a one-frame test first, because
 * ffmpeg lists encoders it was built with, not ones the machine can run.
 */
public final class VideoEncoder implements AutoCloseable {

    public static final int QUEUE = 3;

    /** How good the video should be: picks the encoder's quality setting. */
    public enum Quality { STANDARD, HIGH, BEST }

    /** One way to encode: the ffmpeg encoder name and its options for a quality. */
    public record Codec(String name, String label, boolean hardware) {}

    /** Encoders to try, best first, for a system (SystemOpen.Os name: windows, mac, linux). */
    public static List<Codec> candidates(String os) {
        List<Codec> out = new ArrayList<>();
        switch (os) {
            case "windows" -> {
                out.add(new Codec("h264_nvenc", "NVIDIA NVENC", true));
                out.add(new Codec("h264_amf", "AMD AMF", true));
                out.add(new Codec("h264_qsv", "Intel Quick Sync", true));
            }
            case "mac" -> out.add(new Codec("h264_videotoolbox", "Apple VideoToolbox", true));
            default -> {
                out.add(new Codec("h264_nvenc", "NVIDIA NVENC", true));
                out.add(new Codec("h264_vaapi", "VAAPI", true));
                out.add(new Codec("h264_qsv", "Intel Quick Sync", true));
            }
        }
        out.add(new Codec("libx264", "x264 (processor)", false));
        return out;
    }

    /** The encoder's options for a quality, without input or output. */
    public static List<String> codecArgs(Codec c, Quality q) {
        List<String> a = new ArrayList<>();
        int crf = switch (q) { case STANDARD -> 23; case HIGH -> 18; case BEST -> 14; };
        switch (c.name()) {
            case "libx264" -> {
                a.addAll(List.of("-c:v", "libx264", "-preset", q == Quality.BEST ? "slow" : "medium", "-crf", String.valueOf(crf)));
                a.addAll(List.of("-pix_fmt", "yuv420p"));
            }
            case "h264_nvenc" -> {
                a.addAll(List.of("-c:v", "h264_nvenc", "-preset", "p5", "-rc", "vbr", "-cq", String.valueOf(crf + 1), "-b:v", "0"));
                a.addAll(List.of("-pix_fmt", "yuv420p"));
            }
            case "h264_amf" -> {
                a.addAll(List.of("-c:v", "h264_amf", "-quality", "quality", "-rc", "cqp",
                        "-qp_i", String.valueOf(crf), "-qp_p", String.valueOf(crf + 2)));
                a.addAll(List.of("-pix_fmt", "yuv420p"));
            }
            case "h264_qsv" -> {
                a.addAll(List.of("-c:v", "h264_qsv", "-global_quality", String.valueOf(crf + 1)));
                a.addAll(List.of("-pix_fmt", "nv12"));
            }
            case "h264_videotoolbox" -> {
                int qv = switch (q) { case STANDARD -> 60; case HIGH -> 72; case BEST -> 85; };
                a.addAll(List.of("-c:v", "h264_videotoolbox", "-q:v", String.valueOf(qv)));
                a.addAll(List.of("-pix_fmt", "yuv420p"));
            }
            case "h264_vaapi" -> {
                // Frames are uploaded to the card after conversion (see command()).
                a.addAll(List.of("-c:v", "h264_vaapi", "-qp", String.valueOf(crf)));
            }
            default -> a.addAll(List.of("-c:v", c.name()));
        }
        return a;
    }

    /**
     * The whole ffmpeg command: raw RGBA frames of w x h at fps on standard
     * input, bottom row first as OpenGL reads them (flipped here), into an
     * MP4 that starts playing before it is fully downloaded.
     */
    public static List<String> command(String ffmpeg, Codec c, Quality q, int w, int h, int fps, Path out) {
        return command(ffmpeg, c, q, w, h, fps, out, true);
    }

    /**
     * The same, with bottomUp false for frames already top row first (the
     * game's own screenshot read-back). Odd sizes lose their last row or
     * column: the H.264 colour format needs even ones.
     */
    public static List<String> command(String ffmpeg, Codec c, Quality q, int w, int h, int fps, Path out, boolean bottomUp) {
        List<String> a = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-y"));
        if ("h264_vaapi".equals(c.name())) a.addAll(List.of("-vaapi_device", "/dev/dri/renderD128"));
        a.addAll(List.of("-f", "rawvideo", "-pix_fmt", "rgba", "-s", w + "x" + h, "-framerate", String.valueOf(fps), "-i", "-"));
        String vf = (bottomUp ? "vflip," : "") + "crop=trunc(iw/2)*2:trunc(ih/2)*2";
        if ("h264_vaapi".equals(c.name())) vf += ",format=nv12,hwupload";
        a.addAll(List.of("-vf", vf));
        a.addAll(codecArgs(c, q));
        a.addAll(List.of("-r", String.valueOf(fps), "-movflags", "+faststart", "-an", out.toAbsolutePath().toString()));
        return a;
    }

    /** The first encoder that encodes a small test video on this computer, or null when none (ffmpeg missing). */
    public static Codec pick(String ffmpeg, String os, Path scratch) {
        for (Codec c : candidates(os)) {
            if (works(ffmpeg, c, scratch)) return c;
        }
        return null;
    }

    static boolean works(String ffmpeg, Codec c, Path scratch) {
        Path out = scratch.resolve("breeze-encoder-test.mp4");
        try {
            int w = 256, h = 144;
            List<String> cmd = command(ffmpeg, c, Quality.STANDARD, w, h, 30, out);
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            drain(p.getInputStream());
            byte[] frame = new byte[w * h * 4];
            try (OutputStream o = p.getOutputStream()) {
                for (int i = 0; i < 3; i++) o.write(frame);
            } catch (IOException e) {
                // The encoder refused before reading: judged by the exit code below.
            }
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0 && out.toFile().length() > 0;
        } catch (Throwable t) {
            return false;
        } finally {
            out.toFile().delete();
        }
    }

    private static void drain(InputStream in) {
        Thread t = new Thread(() -> {
            try (in) {
                in.transferTo(OutputStream.nullOutputStream());
            } catch (IOException ignored) {}
        }, "Breeze ffmpeg output");
        t.setDaemon(true);
        t.start();
    }

    // ── A running export ───────────────────────────────────────────────────

    private static final byte[] END = new byte[0];

    private final Process process;
    private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE);
    private final BlockingQueue<byte[]> free = new ArrayBlockingQueue<>(QUEUE + 1);
    private final Thread writer;
    private final StringBuilder errors = new StringBuilder();
    private final int frameBytes;
    private volatile String failure;
    private long frames;

    public VideoEncoder(List<String> command, int w, int h) throws IOException {
        frameBytes = w * h * 4;
        process = new ProcessBuilder(command).start();
        Thread err = new Thread(() -> {
            try (InputStream in = process.getErrorStream()) {
                byte[] b = in.readAllBytes();
                synchronized (errors) {
                    errors.append(new String(b, StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {}
        }, "Breeze ffmpeg errors");
        err.setDaemon(true);
        err.start();
        drain(process.getInputStream());
        writer = new Thread(this::write, "Breeze export writer");
        writer.setDaemon(true);
        writer.start();
    }

    /** A buffer for the next frame (reused; never more than a few exist). */
    public byte[] buffer() {
        byte[] b = free.poll();
        return b != null && b.length == frameBytes ? b : new byte[frameBytes];
    }

    /** Hands one frame over; waits while the encoder is behind (the export just takes longer). False once it failed. */
    public boolean frame(byte[] rgba) {
        if (failure != null) return false;
        try {
            while (!queue.offer(rgba, 200, TimeUnit.MILLISECONDS)) {
                if (failure != null || !process.isAlive()) return false;
            }
            frames++;
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public long framesSent() {
        return frames;
    }

    private void write() {
        try (OutputStream o = process.getOutputStream()) {
            while (true) {
                byte[] f = queue.take();
                if (f == END) break;
                o.write(f);
                free.offer(f);
            }
        } catch (Throwable t) {
            failure = "ffmpeg stopped taking frames";
        }
    }

    /** Ends the video and waits for ffmpeg; null when it worked, else what went wrong. */
    public String finish() {
        try {
            queue.put(END);
            writer.join(120_000);
            if (!process.waitFor(300, TimeUnit.SECONDS)) {
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
            return "the export was interrupted";
        }
    }

    /** Stops at once and leaves no half-written video behind (the caller deletes it). */
    @Override
    public void close() {
        queue.clear();
        queue.offer(END);
        process.destroy();
    }

    /** "windows", "mac" or "linux" from os.name. */
    public static String osKey(String osName) {
        String n = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (n.startsWith("windows")) return "windows";
        if (n.startsWith("mac") || n.contains("darwin")) return "mac";
        return "linux";
    }
}
