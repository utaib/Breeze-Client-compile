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

    /**
     * Asks the Breeze launcher for ffmpeg (it watches for
     * {@code <breeze.home>/ffmpeg/wanted} and downloads the build for this
     * system) and says what to do meanwhile.
     */
    public static String request() {
        String home = System.getProperty("breeze.home");
        if (home == null || home.isBlank()) {
            return "Exporting needs ffmpeg: start Minecraft from the Breeze launcher, which gets it for this computer.";
        }
        try {
            Path dir = Path.of(home).resolve("ffmpeg");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("wanted"), "export");
        } catch (Exception e) {
            return "Exporting needs ffmpeg, and Breeze could not ask the launcher for it: " + e.getMessage();
        }
        return "Exporting needs ffmpeg: the Breeze launcher is getting it now. Export again in a minute.";
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
