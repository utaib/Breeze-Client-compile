package dev.breeze.recorder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Where ffmpeg is: the copy the launcher downloads for this system into
 * Breeze's folder ({@code <breeze.home>/ffmpeg/}), else one named by
 * {@code -Dbreeze.ffmpeg}, else one on the system's PATH. Null when there is
 * none; export then says the launcher fetches it.
 */
public final class Ffmpeg {

    private Ffmpeg() {}

    public static String find() {
        String explicit = System.getProperty("breeze.ffmpeg");
        if (explicit != null && !explicit.isBlank() && Files.isExecutable(Path.of(explicit))) return explicit;
        String home = System.getProperty("breeze.home");
        if (home != null && !home.isBlank()) {
            for (String n : new String[]{"ffmpeg.exe", "ffmpeg"}) {
                Path p = Path.of(home).resolve("ffmpeg").resolve(n);
                if (Files.isExecutable(p)) return p.toString();
            }
        }
        return runs("ffmpeg") ? "ffmpeg" : null;
    }

    static boolean runs(String program) {
        try {
            Process p = new ProcessBuilder(program, "-version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
