package dev.breeze.recorder;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Joins the screen video and its sound tracks into one MP4 when recording
 * stops: the video is copied as it is (not encoded again), each track is put
 * in line with it by the moments both started (sound before the video's
 * first frame is cut, a late start is padded with silence), cut to the
 * video's length and encoded once to AAC, and named, so a player or an
 * editor shows "Game" and "Microphone" as separate tracks that can be muted
 * on their own. The first track plays by default.
 *
 * The result is written beside the video and moved over it only when
 * ffmpeg finished, so the fragmented video (which plays even after a crash)
 * stays until then.
 */
public final class SoundMux {

    /** Everything is mixed at this rate. */
    public static final int RATE = 48_000;

    /** A track to add: its samples, what its info file said, and its name in the video. */
    public record Input(Path pcm, SoundTrack.Info info, String title) {}

    private SoundMux() {}

    /**
     * The ffmpeg command. {@code videoStartUs} is the video's first frame on
     * the recording's clock; {@code durationSec} its length (0 or less when
     * unknown: the shortest stream then ends it).
     */
    public static List<String> command(String ffmpeg, Path video, List<Input> tracks, long videoStartUs, double durationSec, Path out) {
        List<String> a = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-i", video.toAbsolutePath().toString()));
        StringBuilder fc = new StringBuilder();
        for (int i = 0; i < tracks.size(); i++) {
            Input t = tracks.get(i);
            SoundTrack.Info in = t.info();
            boolean empty = in == null || in.rate() <= 0 || in.channels() <= 0 || size(t.pcm()) < 2L * Math.max(1, in.channels());
            if (empty) {
                a.addAll(List.of("-f", "lavfi", "-i", "anullsrc=r=" + RATE + ":cl=stereo"));
            } else {
                a.addAll(List.of("-f", "s16le", "-ar", String.valueOf(in.rate()), "-ac", String.valueOf(in.channels()),
                        "-i", t.pcm().toAbsolutePath().toString()));
            }
            if (i > 0) fc.append(';');
            fc.append('[').append(i + 1).append(":a]");
            if (!empty) fc.append(align(in, videoStartUs));
            fc.append("aresample=").append(RATE).append(",aformat=sample_fmts=fltp:channel_layouts=stereo,apad");
            if (durationSec > 0) fc.append(String.format(Locale.ROOT, ",atrim=end=%.6f", durationSec));
            fc.append("[a").append(i).append(']');
        }
        if (!tracks.isEmpty()) a.addAll(List.of("-filter_complex", fc.toString()));
        a.addAll(List.of("-map", "0:v:0"));
        for (int i = 0; i < tracks.size(); i++) a.addAll(List.of("-map", "[a" + i + "]"));
        a.addAll(List.of("-c:v", "copy"));
        if (!tracks.isEmpty()) a.addAll(List.of("-c:a", "aac", "-b:a", "192k"));
        for (int i = 0; i < tracks.size(); i++) {
            String title = tracks.get(i).title();
            a.addAll(List.of("-metadata:s:a:" + i, "title=" + title, "-metadata:s:a:" + i, "handler_name=" + title,
                    "-disposition:a:" + i, i == 0 ? "default" : "0"));
        }
        if (durationSec <= 0) a.add("-shortest");
        // The first frame's moment, so a render of the world replay can take this sound in time (VideoProbe).
        a.addAll(List.of("-metadata", "comment=" + VideoProbe.START_KEY + videoStartUs));
        a.addAll(List.of("-movflags", "+faststart", out.toAbsolutePath().toString()));
        return a;
    }

    /**
     * The filter that puts a track in line with the video: delayed by the
     * time it started after the first frame, or with the part before the
     * first frame cut off.
     */
    static String align(SoundTrack.Info in, long videoStartUs) {
        long offsetUs = in.startUs() - videoStartUs;
        if (offsetUs > 0) {
            long samples = offsetUs * in.rate() / 1_000_000L;
            return samples > 0 ? "adelay=delays=" + samples + "S:all=1," : "";
        }
        if (offsetUs < 0) {
            return String.format(Locale.ROOT, "atrim=start=%.6f,asetpts=PTS-STARTPTS,", -offsetUs / 1e6);
        }
        return "";
    }

    private static long size(Path p) {
        try {
            return Files.size(p);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Runs the join and puts the result in the video's place. Null when it
     * worked, else what went wrong (the video is then left as it was).
     */
    public static String run(String ffmpeg, Path video, List<Input> tracks, long videoStartUs, double durationSec) {
        Path out = video.resolveSibling(stem(video.getFileName().toString()) + ".sound.mp4");
        try {
            Process p = new ProcessBuilder(command(ffmpeg, video, tracks, videoStartUs, durationSec, out)).redirectErrorStream(true).start();
            String log;
            try (InputStream in = p.getInputStream()) {
                log = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            if (!p.waitFor(600, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                Files.deleteIfExists(out);
                return "ffmpeg did not finish adding the sound";
            }
            if (p.exitValue() != 0 || !Files.isRegularFile(out) || Files.size(out) == 0) {
                Files.deleteIfExists(out);
                return "ffmpeg could not add the sound" + (log.isEmpty() ? "" : ": " + log.lines().reduce((x, y) -> y).orElse(""));
            }
            try {
                Files.move(out, video, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                Files.move(out, video, StandardCopyOption.REPLACE_EXISTING);
            }
            return null;
        } catch (Exception e) {
            try {
                Files.deleteIfExists(out);
            } catch (Exception ignored) {}
            return "the sound could not be added: " + e.getMessage();
        } catch (Throwable t) {
            return "the sound could not be added: " + t;
        }
    }

    /**
     * The command that gives a render of the world replay ({@code render},
     * which starts at the recording's start, {@code renderStartUs}) the sound
     * tracks of the screen video made at the same time ({@code source}, whose
     * first frame was at {@code sourceStartUs}), in time and named as there.
     */
    public static List<String> carryCommand(String ffmpeg, Path render, Path source, List<String> titles, long renderStartUs,
                                            long sourceStartUs, double durationSec, Path out) {
        List<String> a = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                "-i", render.toAbsolutePath().toString(), "-i", source.toAbsolutePath().toString()));
        SoundTrack.Info at = new SoundTrack.Info(RATE, 2, sourceStartUs, 0, "done", null);
        StringBuilder fc = new StringBuilder();
        for (int i = 0; i < titles.size(); i++) {
            if (i > 0) fc.append(';');
            fc.append("[1:a:").append(i).append("]aresample=").append(RATE).append(',').append(align(at, renderStartUs))
                    .append("aformat=sample_fmts=fltp:channel_layouts=stereo,apad");
            if (durationSec > 0) fc.append(String.format(Locale.ROOT, ",atrim=end=%.6f", durationSec));
            fc.append("[a").append(i).append(']');
        }
        a.addAll(List.of("-filter_complex", fc.toString(), "-map", "0:v:0"));
        for (int i = 0; i < titles.size(); i++) a.addAll(List.of("-map", "[a" + i + "]"));
        a.addAll(List.of("-c:v", "copy", "-c:a", "aac", "-b:a", "192k"));
        for (int i = 0; i < titles.size(); i++) {
            a.addAll(List.of("-metadata:s:a:" + i, "title=" + titles.get(i), "-metadata:s:a:" + i, "handler_name=" + titles.get(i),
                    "-disposition:a:" + i, i == 0 ? "default" : "0"));
        }
        if (durationSec <= 0) a.add("-shortest");
        a.addAll(List.of("-movflags", "+faststart", out.toAbsolutePath().toString()));
        return a;
    }

    /** Runs {@link #carryCommand} and puts the result in the render's place; null when it worked. */
    public static String carry(String ffmpeg, Path render, Path source, List<String> titles, long renderStartUs, long sourceStartUs,
                               double durationSec) {
        if (titles.isEmpty()) return null;
        Path out = render.resolveSibling(stem(render.getFileName().toString()) + ".sound.mp4");
        return exec(carryCommand(ffmpeg, render, source, titles, renderStartUs, sourceStartUs, durationSec, out), out, render);
    }

    private static String exec(List<String> cmd, Path out, Path target) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String log;
            try (InputStream in = p.getInputStream()) {
                log = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            if (!p.waitFor(600, TimeUnit.SECONDS) || p.exitValue() != 0 || !Files.isRegularFile(out) || Files.size(out) == 0) {
                p.destroyForcibly();
                Files.deleteIfExists(out);
                return "ffmpeg could not add the sound" + (log.isEmpty() ? "" : ": " + log.lines().reduce((x, y) -> y).orElse(""));
            }
            try {
                Files.move(out, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                Files.move(out, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return null;
        } catch (Exception e) {
            try {
                Files.deleteIfExists(out);
            } catch (Exception ignored) {}
            return "the sound could not be added: " + e.getMessage();
        }
    }

    static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
