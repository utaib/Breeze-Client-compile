package dev.breeze.recorder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The export editor's Export: the video as it is (copied, not encoded
 * again) with its tracks mixed at the chosen volumes into one sound track,
 * into a new MP4 in the videos folder. Runs on its own thread with progress
 * from ffmpeg; only {@link #cancel} stops it (a cancelled export leaves no
 * file).
 */
public final class VideoMix {

    private final Path out;
    private final Thread thread;
    private volatile Process process;
    private volatile double progress;
    private volatile boolean done, cancelled;
    private volatile String result;

    private VideoMix(String ffmpeg, Path video, VideoProbe info, double gameVol, double micVol, Path out) {
        this.out = out;
        List<String> cmd = command(ffmpeg, video, info, gameVol, micVol, out);
        thread = new Thread(() -> run(cmd, info.seconds()), "Breeze export");
        thread.setDaemon(true);
        thread.start();
    }

    /** Starts the export of {@code video} into the same folder, named {@code <name>_export.mp4} (numbered when taken). */
    public static VideoMix start(String ffmpeg, Path video, VideoProbe info, double gameVol, double micVol) {
        return new VideoMix(ffmpeg, video, info, gameVol, micVol, outFor(video));
    }

    static Path outFor(Path video) {
        String stem = SoundMux.stem(video.getFileName().toString());
        Path dir = video.toAbsolutePath().getParent();
        Path out = dir.resolve(stem + "_export.mp4");
        for (int i = 2; Files.exists(out); i++) out = dir.resolve(stem + "_export_" + i + ".mp4");
        return out;
    }

    public static List<String> command(String ffmpeg, Path video, VideoProbe info, double gameVol, double micVol, Path out) {
        List<String> a = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                "-i", video.toAbsolutePath().toString()));
        String mix = VideoProbe.mixFilter(info, gameVol, micVol);
        if (mix != null) a.addAll(List.of("-filter_complex", mix, "-map", "0:v:0", "-map", "[a]", "-c:a", "aac", "-b:a", "192k"));
        else a.addAll(List.of("-map", "0:v:0", "-an"));
        a.addAll(List.of("-c:v", "copy", "-movflags", "+faststart", "-progress", "pipe:1", "-nostats", out.toAbsolutePath().toString()));
        return a;
    }

    private void run(List<String> cmd, double seconds) {
        try {
            Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            process = p;
            if (cancelled) p.destroyForcibly();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("out_time_us=") || line.startsWith("out_time_ms=")) {
                        try {
                            long us = Long.parseLong(line.substring(line.indexOf('=') + 1).trim());
                            if (seconds > 0) progress = Math.min(1, us / 1e6 / seconds);
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
            boolean ok = p.waitFor(600, TimeUnit.SECONDS) && p.exitValue() == 0 && !cancelled && Files.size(out) > 0;
            if (!ok) Files.deleteIfExists(out);
            result = cancelled ? "Export cancelled." : ok ? null : "ffmpeg could not make the video.";
            if (ok) progress = 1;
        } catch (Exception e) {
            try {
                Files.deleteIfExists(out);
            } catch (Exception ignored) {}
            result = cancelled ? "Export cancelled." : "The export failed: " + e.getMessage();
        }
        done = true;
    }

    public void cancel() {
        cancelled = true;
        Process p = process;
        if (p != null) p.destroyForcibly();
    }

    public double progress() {
        return progress;
    }

    public boolean done() {
        return done;
    }

    /** Null when the video was made (once done), else what went wrong. */
    public String result() {
        return result;
    }

    public Path out() {
        return out;
    }

    /** Waits for the end (tests). */
    public void join(long ms) throws InterruptedException {
        thread.join(ms);
    }
}
