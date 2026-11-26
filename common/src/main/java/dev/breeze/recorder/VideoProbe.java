package dev.breeze.recorder;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a video is, read with ffmpeg: its length, picture size, its audio
 * tracks by name, and the moment its first frame was shown (the recorder
 * writes that into the screen video, so a render of the world replay can
 * take the same sound in time). For the export editor.
 */
public record VideoProbe(double seconds, int width, int height, List<String> tracks, long startUs) {

    public static final String START_KEY = "breeze_start_us=";

    /** The track of the game's sound, or -1 (a video with one unnamed track counts it as the game's). */
    public int gameTrack() {
        int i = tracks.indexOf("Game");
        if (i >= 0) return i;
        return tracks.size() == 1 && !"Microphone".equals(tracks.get(0)) ? 0 : -1;
    }

    public int micTrack() {
        return tracks.indexOf("Microphone");
    }

    /** Reads a video; null when ffmpeg cannot. */
    public static VideoProbe of(String ffmpeg, Path video) {
        String info = run(List.of(ffmpeg, "-hide_banner", "-i", video.toAbsolutePath().toString()));
        Matcher d = Pattern.compile("Duration: (\\d+):(\\d+):(\\d+(?:\\.\\d+)?)").matcher(info);
        if (!d.find()) return null;
        double sec = Integer.parseInt(d.group(1)) * 3600 + Integer.parseInt(d.group(2)) * 60 + Double.parseDouble(d.group(3));
        Matcher s = Pattern.compile("Video: [^\\n]*?, (\\d{2,5})x(\\d{2,5})").matcher(info);
        int w = 0, h = 0;
        if (s.find()) {
            w = Integer.parseInt(s.group(1));
            h = Integer.parseInt(s.group(2));
        }
        Matcher st = Pattern.compile(Pattern.quote(START_KEY) + "(-?\\d+)").matcher(info);
        long start = st.find() ? Long.parseLong(st.group(1)) : 0;
        return new VideoProbe(sec, w, h, SoundProbe.tracksOf(info), start);
    }

    static String run(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            byte[] b;
            try (InputStream in = p.getInputStream()) {
                b = in.readAllBytes();
            }
            p.waitFor(30, TimeUnit.SECONDS);
            return new String(b, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** The audio filter that mixes a video's tracks at the given volumes (1 = as recorded, 0 = muted), or null for silence. */
    public static String mixFilter(VideoProbe v, double gameVol, double micVol) {
        List<String> parts = new ArrayList<>();
        int g = v.gameTrack(), m = v.micTrack();
        if (g >= 0 && gameVol > 0) parts.add(String.format(java.util.Locale.ROOT, "[0:a:%d]volume=%.3f[g]", g, gameVol));
        if (m >= 0 && micVol > 0) parts.add(String.format(java.util.Locale.ROOT, "[0:a:%d]volume=%.3f[m]", m, micVol));
        if (parts.isEmpty()) return null;
        String f = String.join(";", parts);
        if (parts.size() == 2) return f + ";[g][m]amix=inputs=2:duration=longest:normalize=0[a]";
        return f.replace("[g]", "[a]").replace("[m]", "[a]");
    }
}
