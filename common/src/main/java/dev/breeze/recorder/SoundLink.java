package dev.breeze.recorder;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * How the mod asks the Breeze launcher for the game's own sound: files in
 * the instance's {@code recordings/.sound/} folder, as {@code recordings/.open}
 * and {@code ffmpeg/wanted} already work. No socket, no port.
 *
 * <ul>
 *   <li>{@code ready}: written by the launcher when it starts the game, and
 *   removed when the game ends. It names the game's process id and whether
 *   this system can record one program's sound ({@code game=1}: Windows 10
 *   version 2004 or later), else why not. The mod trusts it only when the
 *   process id is its own, so a file left by an earlier game, or by another
 *   launcher, says nothing.</li>
 *   <li>{@code start}: written by the mod with the recording's name. The
 *   launcher deletes it and records the sound of the game's process (and the
 *   processes it started, not Discord or Spotify) into
 *   {@code <name>.game.pcm} with {@code <name>.game.info}
 *   ({@link SoundTrack}'s form).</li>
 *   <li>{@code stop}: written by the mod with the same name; the launcher
 *   ends the track and marks its info file done. It also ends it when the
 *   game exits.</li>
 * </ul>
 *
 * The launcher only ever writes inside that folder, under a name it checks
 * ({@link #validName}), and only records the game it started.
 */
public final class SoundLink {

    public static final String DIR = ".sound";
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,96}");

    private SoundLink() {}

    public static Path dir(Path gameDir) {
        return gameDir.resolve("recordings").resolve(DIR);
    }

    public static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches() && !name.contains("..");
    }

    /** What the launcher said about the game's sound. */
    public record Ready(boolean available, String reason) {}

    /**
     * Whether the launcher that started this game can record its sound:
     * available, or the plain reason it cannot (too old a launcher, another
     * system).
     */
    public static Ready ready(Path gameDir, long ownPid) {
        Path f = dir(gameDir).resolve("ready");
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(f)) {
            p.load(in);
        } catch (Exception e) {
            return new Ready(false, "Start the game from the Breeze launcher 1.2.3 or later to record its sound.");
        }
        String pid = p.getProperty("pid", "").trim();
        if (!pid.equals(String.valueOf(ownPid))) {
            return new Ready(false, "Start the game from the Breeze launcher 1.2.3 or later to record its sound.");
        }
        if (!"1".equals(p.getProperty("game", "").trim())) {
            String why = p.getProperty("reason", "").trim();
            return new Ready(false, why.isEmpty() ? "Game sound needs Windows 10 version 2004 or later." : why);
        }
        return new Ready(true, "");
    }

    public static Path pcm(Path gameDir, String name, String kind) {
        return dir(gameDir).resolve(name + "." + kind + ".pcm");
    }

    public static Path info(Path gameDir, String name, String kind) {
        return dir(gameDir).resolve(name + "." + kind + ".info");
    }

    /** Asks the launcher to start recording the game's sound for this recording. */
    public static void askStart(Path gameDir, String name) throws java.io.IOException {
        request(gameDir, "start", name);
    }

    /** Asks the launcher to end it. */
    public static void askStop(Path gameDir, String name) throws java.io.IOException {
        request(gameDir, "stop", name);
    }

    private static void request(Path gameDir, String what, String name) throws java.io.IOException {
        if (!validName(name)) throw new java.io.IOException("bad recording name");
        Path d = dir(gameDir);
        Files.createDirectories(d);
        Path tmp = d.resolve(what + ".tmp");
        Files.writeString(tmp, "name=" + name + "\n", StandardCharsets.UTF_8);
        try {
            Files.move(tmp, d.resolve(what), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.io.IOException e) {
            Files.move(tmp, d.resolve(what), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** The name in a start or stop request (also what the launcher reads), or null. */
    public static String requestName(Path f) {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(f)) {
            p.load(in);
        } catch (Exception e) {
            return null;
        }
        String n = p.getProperty("name", "").trim();
        return validName(n) ? n : null;
    }
}
