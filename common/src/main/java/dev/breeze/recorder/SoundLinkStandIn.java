package dev.breeze.recorder;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The self-test's stand-in for the Breeze launcher's side of {@link SoundLink}
 * (the build machines start the game without the launcher, and have no sound
 * device): writes {@code ready} with this game's process id, answers
 * {@code start} with a generated "game" track (a 440 Hz tone at
 * {@link ToneSource#beepUs}, stamped with the system's time as the launcher
 * stamps it) and {@code stop} by closing it. It goes through the same files
 * as the launcher, so the mod's half of the exchange, the join and the
 * timing are what is tested; the launcher's capture itself is not.
 */
public final class SoundLinkStandIn implements Runnable {

    private final Path gameDir;
    private final Thread thread;
    private volatile boolean stopping;
    private ToneSource tone;
    private String name;
    /** Requests answered (self-test). */
    public volatile int starts, stops;

    public SoundLinkStandIn(Path gameDir, long pid) throws java.io.IOException {
        this.gameDir = gameDir;
        Path d = SoundLink.dir(gameDir);
        Files.createDirectories(d);
        Files.writeString(d.resolve("ready"), "pid=" + pid + "\ngame=1\nversion=1\n");
        thread = new Thread(this, "Breeze test launcher sound");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void run() {
        Path d = SoundLink.dir(gameDir);
        while (!stopping) {
            try {
                Thread.sleep(100);
                Path start = d.resolve("start"), stop = d.resolve("stop");
                if (Files.isRegularFile(start)) {
                    String n = SoundLink.requestName(start);
                    Files.deleteIfExists(start);
                    if (n != null && tone == null) {
                        name = n;
                        tone = new ToneSource(SoundLink.pcm(gameDir, n, "game"), SoundLink.info(gameDir, n, "game"), 440,
                                SoundClock::systemUs, "Breeze test game sound");
                        starts++;
                    }
                }
                if (Files.isRegularFile(stop)) {
                    String n = SoundLink.requestName(stop);
                    Files.deleteIfExists(stop);
                    if (tone != null && n != null && n.equals(name)) {
                        tone.stop();
                        tone = null;
                        stops++;
                    }
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception ignored) {
                // Tried again on the next round.
            }
        }
    }

    public void close() {
        stopping = true;
        thread.interrupt();
        if (tone != null) tone.stop();
        try {
            Files.deleteIfExists(SoundLink.dir(gameDir).resolve("ready"));
        } catch (Exception ignored) {}
    }
}
