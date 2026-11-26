package dev.breeze.recorder;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Reads a finished video's sound back with ffmpeg (the self-test and the
 * unit tests): its audio tracks and their names, and where a tone first
 * sounds in a track.
 */
public final class SoundProbe {

    private SoundProbe() {}

    /** The video's audio tracks, in order, by name ("Game", "Microphone"; "" for one without a name). */
    public static List<String> tracks(String ffmpeg, Path video) {
        return tracksOf(run(List.of(ffmpeg, "-hide_banner", "-i", video.toAbsolutePath().toString())));
    }

    /** The audio tracks named in ffmpeg's description of a file. */
    static List<String> tracksOf(String info) {
        List<String> out = new ArrayList<>();
        boolean inAudio = false;
        for (String line : info.split("\n")) {
            String t = line.trim();
            if (t.startsWith("Stream #")) {
                inAudio = t.contains(": Audio:");
                if (inAudio) out.add("");
            } else if (inAudio && (t.startsWith("title") || t.startsWith("handler_name")) && t.contains(":")) {
                String v = t.substring(t.indexOf(':') + 1).trim();
                int last = out.size() - 1;
                if (out.get(last).isEmpty() || t.startsWith("title")) out.set(last, v);
            }
        }
        return out;
    }

    /**
     * The first moment, in milliseconds from the start of the video, at
     * which audio track {@code index} is louder than a quarter of full
     * scale; -1 when it never is. Also gives the track's length.
     */
    public static double[] onsetAndLengthMs(String ffmpeg, Path video, int index) {
        int rate = 8_000;
        byte[] pcm = bytes(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-i", video.toAbsolutePath().toString(),
                "-map", "0:a:" + index, "-ac", "1", "-ar", String.valueOf(rate), "-f", "s16le", "-"));
        int n = pcm.length / 2;
        double onset = -1;
        for (int i = 0; i < n; i++) {
            int v = (short) ((pcm[2 * i] & 0xff) | (pcm[2 * i + 1] << 8));
            if (Math.abs(v) > 8_000) {
                onset = i * 1000.0 / rate;
                break;
            }
        }
        return new double[]{onset, n * 1000.0 / rate};
    }

    private static String run(List<String> cmd) {
        return new String(bytes(cmd), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(!cmd.contains("-")).start();
            if (cmd.contains("-")) {
                Thread err = new Thread(() -> {
                    try (InputStream e = p.getErrorStream()) {
                        e.transferTo(java.io.OutputStream.nullOutputStream());
                    } catch (Exception ignored) {}
                });
                err.setDaemon(true);
                err.start();
            }
            byte[] b;
            try (InputStream in = p.getInputStream()) {
                b = in.readAllBytes();
            }
            p.waitFor(60, TimeUnit.SECONDS);
            return b;
        } catch (Exception e) {
            return new byte[0];
        }
    }
}
