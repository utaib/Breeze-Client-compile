package dev.breeze.recorder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ffmpeg commands that put sound and video together without encoding the
 * video again (it is copied as it is):
 *
 * <ul>
 *   <li>{@link #join}: after recording, the screen video and the launcher's
 *   game and microphone tracks into one MP4 with the sound as two separate
 *   tracks (Game, Microphone), each moved to start exactly where the video's
 *   first frame was shown and cut or padded to the video's length.</li>
 *   <li>{@link #edit}: the export editor's result: the video (the screen
 *   video, or a render from the world replay) with one stereo track mixed
 *   from the tracks kept at their chosen volumes.</li>
 * </ul>
 */
public final class Mux {

    private Mux() {}

    /** One sound track: where it comes from and how far it starts after the video (seconds; negative when before). */
    public record In(Path file, double offsetSeconds, String title) {}

    /**
     * The filter that moves a track by offset seconds and fits it to
     * duration seconds: delayed when it starts after the video, its start cut
     * off when before, padded with silence at the end, cut at the end.
     */
    static String fit(int input, double offset, double duration, String label) {
        StringBuilder f = new StringBuilder("[").append(input).append(":a]");
        if (offset > 0.0005) {
            f.append(String.format(Locale.ROOT, "adelay=%d:all=1,", Math.round(offset * 1000)));
        } else if (offset < -0.0005) {
            f.append(String.format(Locale.ROOT, "atrim=start=%.4f,asetpts=PTS-STARTPTS,", -offset));
        }
        f.append("apad,").append(String.format(Locale.ROOT, "atrim=duration=%.4f", duration)).append("[").append(label).append("]");
        return f.toString();
    }

    /** The screen video with its sound tracks (each may be absent), into out as a normal MP4 that starts playing at once. */
    public static List<String> join(String ffmpeg, Path video, List<In> tracks, double durationSeconds, Path out) {
        List<String> a = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-i", video.toAbsolutePath().toString()));
        for (In t : tracks) a.addAll(List.of("-i", t.file().toAbsolutePath().toString()));
        a.addAll(List.of("-map", "0:v:0", "-c:v", "copy"));
        if (!tracks.isEmpty()) {
            StringBuilder fc = new StringBuilder();
            for (int i = 0; i < tracks.size(); i++) {
                if (i > 0) fc.append(';');
                fc.append(fit(i + 1, tracks.get(i).offsetSeconds(), durationSeconds, "a" + i));
            }
            a.addAll(List.of("-filter_complex", fc.toString()));
            for (int i = 0; i < tracks.size(); i++) a.addAll(List.of("-map", "[a" + i + "]"));
            a.addAll(List.of("-c:a", "aac", "-b:a", "192k", "-ar", "48000"));
            for (int i = 0; i < tracks.size(); i++) {
                // MP4 keeps a track's name as its handler name.
                a.addAll(List.of("-metadata:s:a:" + i, "title=" + tracks.get(i).title(), "-metadata:s:a:" + i, "handler_name=" + tracks.get(i).title()));
                a.addAll(List.of("-disposition:a:" + i, i == 0 ? "default" : "0"));
            }
        }
        a.addAll(List.of("-movflags", "+faststart", out.toAbsolutePath().toString()));
        return a;
    }

    /**
     * The export: video from videoFile (its first video stream, copied), and
     * one stereo track mixed from soundFile's audio tracks: index i of
     * volumes is track i's volume (1 as recorded, 0 muted). With no track
     * left the video has no sound. soundOffset moves the sound against the
     * video (seconds), for a render whose first frame is not the screen
     * video's.
     */
    public static List<String> edit(String ffmpeg, Path videoFile, Path soundFile, double[] volumes, double soundOffset, Path out) {
        List<String> a = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-i", videoFile.toAbsolutePath().toString()));
        List<Integer> kept = new ArrayList<>();
        for (int i = 0; i < volumes.length; i++) if (volumes[i] > 0.001) kept.add(i);
        boolean sound = !kept.isEmpty() && soundFile != null;
        int si = 0;
        if (sound) {
            if (soundFile.equals(videoFile) && Math.abs(soundOffset) < 0.0005) {
                si = 0;
            } else {
                if (Math.abs(soundOffset) >= 0.0005) a.addAll(List.of("-itsoffset", String.format(Locale.ROOT, "%.4f", soundOffset)));
                a.addAll(List.of("-i", soundFile.toAbsolutePath().toString()));
                si = 1;
            }
        }
        a.addAll(List.of("-map", "0:v:0", "-c:v", "copy"));
        if (sound) {
            StringBuilder fc = new StringBuilder();
            for (int k = 0; k < kept.size(); k++) {
                int i = kept.get(k);
                if (k > 0) fc.append(';');
                fc.append(String.format(Locale.ROOT, "[%d:a:%d]volume=%.3f[v%d]", si, i, volumes[i], k));
            }
            if (kept.size() == 1) {
                a.addAll(List.of("-filter_complex", fc.toString(), "-map", "[v0]"));
            } else {
                fc.append(';');
                for (int k = 0; k < kept.size(); k++) fc.append("[v").append(k).append(']');
                // normalize=0: each track keeps the volume chosen for it, not divided by the number of tracks.
                fc.append("amix=inputs=").append(kept.size()).append(":duration=first:normalize=0[mix]");
                a.addAll(List.of("-filter_complex", fc.toString(), "-map", "[mix]"));
            }
            a.addAll(List.of("-c:a", "aac", "-b:a", "192k", "-ac", "2", "-shortest"));
        } else {
            a.add("-an");
        }
        a.addAll(List.of("-movflags", "+faststart", out.toAbsolutePath().toString()));
        return a;
    }

    /**
     * A small preview the game can show while editing: raw RGBA frames of
     * w by h at fps from start seconds, on standard output, decoded by ffmpeg
     * (any computer, no browser codec needed).
     */
    public static List<String> preview(String ffmpeg, Path video, double startSeconds, int w, int h, int fps) {
        return List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-ss", String.format(Locale.ROOT, "%.3f", Math.max(0, startSeconds)),
                "-i", video.toAbsolutePath().toString(), "-an",
                "-vf", "fps=" + fps + ",scale=" + w + ":" + h + ":flags=bilinear,format=rgba",
                "-f", "rawvideo", "-pix_fmt", "rgba", "-");
    }

    /**
     * The preview's sound, mixed at the editor's volumes: 16-bit stereo
     * 44.1 kHz PCM on standard output from start seconds (Java plays it).
     */
    public static List<String> previewSound(String ffmpeg, Path video, double startSeconds, double[] volumes) {
        List<String> a = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-ss", String.format(Locale.ROOT, "%.3f", Math.max(0, startSeconds)),
                "-i", video.toAbsolutePath().toString(), "-vn"));
        List<Integer> kept = new ArrayList<>();
        for (int i = 0; i < volumes.length; i++) if (volumes[i] > 0.001) kept.add(i);
        if (kept.isEmpty()) return null;
        StringBuilder fc = new StringBuilder();
        for (int k = 0; k < kept.size(); k++) {
            if (k > 0) fc.append(';');
            fc.append(String.format(Locale.ROOT, "[0:a:%d]volume=%.3f[v%d]", kept.get(k), volumes[kept.get(k)], k));
        }
        if (kept.size() > 1) {
            fc.append(';');
            for (int k = 0; k < kept.size(); k++) fc.append("[v").append(k).append(']');
            fc.append("amix=inputs=").append(kept.size()).append(":normalize=0[mix]");
            a.addAll(List.of("-filter_complex", fc.toString(), "-map", "[mix]"));
        } else {
            a.addAll(List.of("-filter_complex", fc.toString(), "-map", "[v0]"));
        }
        a.addAll(List.of("-ac", "2", "-ar", "44100", "-f", "s16le", "-"));
        return a;
    }
}
