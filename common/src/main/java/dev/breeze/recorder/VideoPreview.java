package dev.breeze.recorder;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The export editor's preview: the video played inside the game. ffmpeg
 * decodes it on its own process into small RGBA frames at {@link #FPS} in
 * real time (the game draws the newest one), and a second ffmpeg mixes its
 * sound at the editor's volumes into Java's sound output, so a change of
 * volume or a mute is heard as it will be exported. Changing a volume, or
 * seeking, starts both again from the position. Minecraft's own browser
 * cannot play H.264 (its Chromium has no such decoder) and is missing on
 * some versions, so the picture goes through a texture instead.
 */
public final class VideoPreview implements AutoCloseable {

    public static final int FPS = 20;

    private final String ffmpeg;
    private final Path video;
    private final VideoProbe info;
    private final int w, h;
    private final double from;
    private final List<Process> processes = new ArrayList<>();
    private volatile boolean closed;
    private final Object lock = new Object();
    private byte[] latest;
    private long latestSeq, framesRead;
    private volatile boolean ended;
    private volatile String audioProblem;
    private javax.sound.sampled.SourceDataLine line;

    /**
     * Starts playing {@code video} from {@code fromSec} with the given track
     * volumes (0 mutes), frames scaled to fit {@code maxW} by {@code maxH}.
     */
    public VideoPreview(String ffmpeg, Path video, VideoProbe info, double fromSec, double gameVol, double micVol, int maxW, int maxH) {
        this.ffmpeg = ffmpeg;
        this.video = video;
        this.info = info;
        int[] s = size(info.width(), info.height(), maxW, maxH);
        this.w = s[0];
        this.h = s[1];
        this.from = Math.max(0, Math.min(fromSec, Math.max(0, info.seconds() - 0.05)));
        Thread v = new Thread(this::pictures, "Breeze preview pictures");
        v.setDaemon(true);
        v.start();
        String mix = VideoProbe.mixFilter(info, gameVol, micVol);
        if (mix != null) {
            Thread a = new Thread(() -> sound(mix), "Breeze preview sound");
            a.setDaemon(true);
            a.start();
        }
    }

    /** The preview's size for a video of vw by vh inside maxW by maxH (even, at least 2). */
    public static int[] size(int vw, int vh, int maxW, int maxH) {
        if (vw <= 0 || vh <= 0) return new int[]{Math.max(2, maxW & ~1), Math.max(2, maxH & ~1)};
        double s = Math.min(maxW / (double) vw, maxH / (double) vh);
        s = Math.min(1.0, s);
        return new int[]{Math.max(2, (int) (vw * s) & ~1), Math.max(2, (int) (vh * s) & ~1)};
    }

    public int width() {
        return w;
    }

    public int height() {
        return h;
    }

    /** Where the preview is in the video, in seconds. */
    public double position() {
        synchronized (lock) {
            return Math.min(info.seconds(), from + framesRead / (double) FPS);
        }
    }

    public boolean ended() {
        return ended;
    }

    /** Why the sound does not play (no sound output), or null. */
    public String audioProblem() {
        return audioProblem;
    }

    /** Frames decoded so far (self-test). */
    public long framesRead() {
        synchronized (lock) {
            return framesRead;
        }
    }

    /**
     * The newest frame (RGBA, top row first, w by h) when it is newer than
     * {@code seenSeq}, else null. {@link #seq} says which it is.
     */
    public byte[] frameAfter(long seenSeq) {
        synchronized (lock) {
            return latestSeq > seenSeq ? latest : null;
        }
    }

    public long seq() {
        synchronized (lock) {
            return latestSeq;
        }
    }

    private Process start(List<String> cmd) throws java.io.IOException {
        synchronized (processes) {
            if (closed) throw new java.io.IOException("closed");
            Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            processes.add(p);
            return p;
        }
    }

    private void pictures() {
        String at = String.format(Locale.ROOT, "%.3f", from);
        List<String> cmd = List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-re", "-ss", at, "-i", video.toAbsolutePath().toString(),
                "-map", "0:v:0", "-vf", "fps=" + FPS + ",scale=" + w + ":" + h + ":flags=bilinear", "-f", "rawvideo", "-pix_fmt", "rgba", "-");
        int size = w * h * 4;
        try {
            Process p = start(cmd);
            try (InputStream in = p.getInputStream()) {
                while (!closed) {
                    byte[] f = in.readNBytes(size);
                    if (f.length < size) break;
                    synchronized (lock) {
                        latest = f;
                        latestSeq++;
                        framesRead++;
                    }
                }
            }
        } catch (Exception ignored) {
            // Closed, or ffmpeg stopped: the preview simply ends.
        }
        ended = true;
    }

    private void sound(String mix) {
        String at = String.format(Locale.ROOT, "%.3f", from);
        try {
            javax.sound.sampled.AudioFormat f = new javax.sound.sampled.AudioFormat(48_000f, 16, 2, true, false);
            javax.sound.sampled.SourceDataLine l = javax.sound.sampled.AudioSystem.getSourceDataLine(f);
            l.open(f, 48_000 / 5 * 4);
            synchronized (processes) {
                if (closed) {
                    l.close();
                    return;
                }
                line = l;
            }
            l.start();
            Process p = start(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-ss", at, "-i", video.toAbsolutePath().toString(),
                    "-filter_complex", mix, "-map", "[a]", "-f", "s16le", "-ar", "48000", "-ac", "2", "-"));
            byte[] buf = new byte[48_000 / 50 * 4];
            try (InputStream in = p.getInputStream()) {
                int n;
                while (!closed && (n = in.readNBytes(buf, 0, buf.length)) > 0) l.write(buf, 0, n - n % 4);
            }
            if (!closed) l.drain();
        } catch (Throwable t) {
            audioProblem = "no sound output";
        }
    }

    @Override
    public void close() {
        synchronized (processes) {
            closed = true;
            for (Process p : processes) p.destroyForcibly();
            if (line != null) {
                try {
                    line.stop();
                    line.flush();
                    line.close();
                } catch (Throwable ignored) {}
            }
        }
    }
}
