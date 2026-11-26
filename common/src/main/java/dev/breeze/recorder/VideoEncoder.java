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

    /**
     * H.264 levels: level_idc, the most macroblocks a second and the most
     * in a frame (ITU-T H.264 table A-1). A level too low for the size and
     * rate is refused by some encoders or makes players drop frames; one is
     * chosen per export ({@link #level}).
     */
    private static final int[][] LEVELS = {
            {31, 108_000, 3_600}, {32, 216_000, 5_120}, {40, 245_760, 8_192}, {42, 522_240, 8_704},
            {50, 589_824, 22_080}, {51, 983_040, 36_864}, {52, 2_073_600, 36_864},
            {60, 4_177_920, 139_264}, {61, 8_355_840, 139_264}, {62, 16_711_680, 139_264}};

    /**
     * The lowest H.264 level (level_idc: 52 is 5.2) that holds a video of w
     * by h at fps, at least 3.1; 62 (the highest) when nothing smaller does.
     */
    public static int level(int w, int h, int fps) {
        long mbs = (long) ((w + 15) / 16) * ((h + 15) / 16);
        long rate = mbs * Math.max(1, fps);
        for (int[] l : LEVELS) {
            // A frame side may be at most sqrt(8 * MaxFS) macroblocks (A.3.1).
            double side = Math.sqrt(8.0 * l[2]);
            if (rate <= l[1] && mbs <= l[2] && (w + 15) / 16 <= side && (h + 15) / 16 <= side) return l[0];
        }
        return 62;
    }

    /** "5.2" for 52. */
    public static String levelName(int idc) {
        return (idc / 10) + "." + (idc % 10);
    }

    /**
     * The level options for an encoder, as level_idc, which every one of
     * them takes in that form (x264 reads 52 as 5.2; NVENC, AMF, VAAPI and
     * Quick Sync use level_idc for their level numbers). VideoToolbox picks
     * its own level from the size and rate.
     */
    static List<String> levelArgs(Codec c, int idc) {
        if (idc <= 0 || "h264_videotoolbox".equals(c.name())) return List.of();
        return List.of("-level:v", String.valueOf(idc));
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
        return command(ffmpeg, c, q, w, h, fps, out, bottomUp, 0, 0);
    }

    /**
     * The same, scaling each frame to outW by outH (Lanczos) when they differ
     * from the frames' size; 0 keeps the frames' size.
     */
    public static List<String> command(String ffmpeg, Codec c, Quality q, int w, int h, int fps, Path out, boolean bottomUp,
                                       int outW, int outH) {
        List<String> a = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-y"));
        if ("h264_vaapi".equals(c.name())) a.addAll(List.of("-vaapi_device", "/dev/dri/renderD128"));
        a.addAll(List.of("-f", "rawvideo", "-pix_fmt", "rgba", "-s", w + "x" + h, "-framerate", String.valueOf(fps), "-i", "-"));
        String vf = (bottomUp ? "vflip," : "") + "crop=trunc(iw/2)*2:trunc(ih/2)*2";
        if (outW > 0 && outH > 0 && (outW != (w & ~1) || outH != (h & ~1))) vf += ",scale=" + outW + ":" + outH + ":flags=lanczos";
        if ("h264_vaapi".equals(c.name())) vf += ",format=nv12,hwupload";
        a.addAll(List.of("-vf", vf));
        a.addAll(codecArgs(c, q));
        int vw = outW > 0 && outH > 0 ? outW : w & ~1, vh = outW > 0 && outH > 0 ? outH : h & ~1;
        a.addAll(levelArgs(c, level(vw, vh, fps)));
        a.addAll(List.of("-r", String.valueOf(fps), "-movflags", "+faststart", "-an", out.toAbsolutePath().toString()));
        return a;
    }

    /**
     * The screen video's ffmpeg command (recorded while playing): raw RGBA
     * frames of w by h, top row first, at fps on standard input, encoded at a
     * speed that keeps up in real time, into a fragmented MP4 (a keyframe and
     * a fragment every second), so a recording cut short by a crash still
     * plays up to its last second.
     */
    public static List<String> liveCommand(String ffmpeg, Codec c, Quality q, int w, int h, int fps, Path out) {
        List<String> a = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-y"));
        if ("h264_vaapi".equals(c.name())) a.addAll(List.of("-vaapi_device", "/dev/dri/renderD128"));
        a.addAll(List.of("-f", "rawvideo", "-pix_fmt", "rgba", "-s", w + "x" + h, "-framerate", String.valueOf(fps), "-i", "-"));
        String vf = "crop=trunc(iw/2)*2:trunc(ih/2)*2";
        if ("h264_vaapi".equals(c.name())) vf += ",format=nv12,hwupload";
        a.addAll(List.of("-vf", vf));
        a.addAll(liveCodecArgs(c, q));
        a.addAll(levelArgs(c, level(w & ~1, h & ~1, fps)));
        a.addAll(List.of("-g", String.valueOf(Math.max(1, fps)), "-r", String.valueOf(fps),
                "-movflags", "+frag_keyframe+empty_moov+default_base_moof", "-an", out.toAbsolutePath().toString()));
        return a;
    }

    /** The encoder's options for a quality at real-time speed (the screen video). */
    public static List<String> liveCodecArgs(Codec c, Quality q) {
        List<String> a = new ArrayList<>();
        int crf = switch (q) { case STANDARD -> 23; case HIGH -> 19; case BEST -> 16; };
        switch (c.name()) {
            case "libx264" -> a.addAll(List.of("-c:v", "libx264", "-preset", q == Quality.BEST ? "faster" : "veryfast",
                    "-crf", String.valueOf(crf), "-pix_fmt", "yuv420p"));
            case "h264_nvenc" -> a.addAll(List.of("-c:v", "h264_nvenc", "-preset", "p4", "-rc", "vbr", "-cq", String.valueOf(crf + 1),
                    "-b:v", "0", "-pix_fmt", "yuv420p"));
            case "h264_amf" -> a.addAll(List.of("-c:v", "h264_amf", "-quality", "balanced", "-rc", "cqp",
                    "-qp_i", String.valueOf(crf), "-qp_p", String.valueOf(crf + 2), "-pix_fmt", "yuv420p"));
            case "h264_qsv" -> a.addAll(List.of("-c:v", "h264_qsv", "-preset", "faster", "-global_quality", String.valueOf(crf + 1),
                    "-pix_fmt", "nv12"));
            case "h264_videotoolbox" -> {
                int qv = switch (q) { case STANDARD -> 60; case HIGH -> 70; case BEST -> 80; };
                a.addAll(List.of("-c:v", "h264_videotoolbox", "-realtime", "1", "-q:v", String.valueOf(qv), "-pix_fmt", "yuv420p"));
            }
            case "h264_vaapi" -> a.addAll(List.of("-c:v", "h264_vaapi", "-qp", String.valueOf(crf)));
            default -> a.addAll(List.of("-c:v", c.name()));
        }
        return a;
    }

    /** The first encoder that encodes a small test video on this computer, or null when none (ffmpeg missing). */
    public static Codec pick(String ffmpeg, String os, Path scratch) {
        return pick(ffmpeg, os, scratch, true);
    }

    /** The same; with hardware false only x264 on the processor is tried. */
    public static Codec pick(String ffmpeg, String os, Path scratch, boolean hardware) {
        for (Codec c : candidates(os)) {
            if (!hardware && c.hardware()) continue;
            if (works(ffmpeg, c, scratch)) return c;
        }
        return null;
    }

    /** x264 on the processor, which takes every size and rate the export offers. */
    public static final Codec X264 = new Codec("libx264", "x264 (processor)", false);

    /**
     * The encoder for the export's real size and rate: c when it encodes a
     * short test at w by h and fps (a graphics card encoder can refuse 4K or
     * 240 fps, or the level they need), else x264, else null.
     */
    public static Codec confirm(String ffmpeg, Codec c, Path scratch, int w, int h, int fps) {
        if (c == null) return null;
        if (works(ffmpeg, c, scratch, w, h, fps)) return c;
        if (!c.hardware()) return null;
        return works(ffmpeg, X264, scratch, w, h, fps) ? X264 : null;
    }

    static boolean works(String ffmpeg, Codec c, Path scratch) {
        return works(ffmpeg, c, scratch, 256, 144, 30);
    }

    static boolean works(String ffmpeg, Codec c, Path scratch, int w, int h, int fps) {
        Path out = scratch.resolve("breeze-encoder-test.mp4");
        try {
            List<String> cmd = command(ffmpeg, c, Quality.STANDARD, w, h, fps, out, false);
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
    private final BlockingQueue<byte[]> queue;
    private final BlockingQueue<byte[]> free;
    private final Thread writer;
    private final StringBuilder errors = new StringBuilder();
    private final int frameBytes;
    private volatile String failure;
    private long frames;

    public VideoEncoder(List<String> command, int w, int h) throws IOException {
        frameBytes = w * h * 4;
        // A 4K frame is 33 MB: fewer of them wait, so memory stays near 100 MB.
        int depth = queueDepth(frameBytes);
        queue = new ArrayBlockingQueue<>(depth);
        free = new ArrayBlockingQueue<>(depth + 1);
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

    /** How many frames may wait for ffmpeg: {@link #QUEUE}, or 1 for frames over 16 MB (1440p and up). */
    static int queueDepth(long frameBytes) {
        return frameBytes > 16L * 1024 * 1024 ? 1 : QUEUE;
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
