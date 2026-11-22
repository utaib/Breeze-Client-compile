package dev.breeze.recorder;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * What the Recorder records, chosen on its preferences screen the first
 * time its key is pressed (and changed later from the Breeze menu). Kept in
 * config/breeze_recorder.properties in the game folder.
 *
 * The screen video is always recorded: exactly what the player sees, read
 * back from the graphics card without waiting and encoded by ffmpeg. The
 * world replay (.breezerec) is kept beside it unless the player chose video
 * only; it lets the recording be rendered again with another resource pack.
 */
public final class RecorderPrefs {

    /** Frame rates offered for the screen video. */
    public static final int[] RATES = {30, 60, 120};
    /** Video heights: 0 is the game window's own size, the others only ever make it smaller. */
    public static final int[] SIZES = {0, 1080, 720, 480};

    /** False until the player saved the preferences screen once. */
    public boolean chosen;
    public boolean keepReplay = true;
    public int fps = 60;
    public int size;
    public VideoEncoder.Quality quality = VideoEncoder.Quality.HIGH;
    public boolean gameSound = true;
    public boolean mic;
    /** The microphone's name as the launcher lists it, or empty for the system's default. */
    public String micDevice = "";
    /** False: always x264 on the processor. */
    public boolean hardware = true;

    private static RecorderPrefs current;
    private static Path file;

    public static synchronized RecorderPrefs get(Path gameDir) {
        Path f = gameDir.resolve("config").resolve("breeze_recorder.properties");
        if (current == null || !f.equals(file)) {
            file = f;
            current = read(f);
        }
        return current;
    }

    static RecorderPrefs read(Path f) {
        RecorderPrefs s = new RecorderPrefs();
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(f)) {
            p.load(in);
        } catch (Exception e) {
            return s;
        }
        s.chosen = "true".equals(p.getProperty("chosen"));
        s.keepReplay = !"false".equals(p.getProperty("keepReplay"));
        s.fps = pick(RATES, parse(p.getProperty("fps"), s.fps), s.fps);
        s.size = pick(SIZES, parse(p.getProperty("size"), s.size), s.size);
        try {
            s.quality = VideoEncoder.Quality.valueOf(p.getProperty("quality", s.quality.name()).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {}
        s.gameSound = !"false".equals(p.getProperty("gameSound"));
        s.mic = "true".equals(p.getProperty("mic"));
        s.micDevice = p.getProperty("micDevice", "");
        s.hardware = !"false".equals(p.getProperty("hardware"));
        return s;
    }

    public synchronized void save() {
        if (file == null) return;
        Properties p = new Properties();
        p.setProperty("chosen", String.valueOf(chosen));
        p.setProperty("keepReplay", String.valueOf(keepReplay));
        p.setProperty("fps", String.valueOf(fps));
        p.setProperty("size", String.valueOf(size));
        p.setProperty("quality", quality.name().toLowerCase(Locale.ROOT));
        p.setProperty("gameSound", String.valueOf(gameSound));
        p.setProperty("mic", String.valueOf(mic));
        p.setProperty("micDevice", micDevice == null ? "" : micDevice);
        p.setProperty("hardware", String.valueOf(hardware));
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream o = Files.newOutputStream(file)) {
                p.store(o, "Breeze recorder preferences");
            }
        } catch (Exception ignored) {
            // Not saved: the choice still holds until the game closes.
        }
    }

    public void nextFps() {
        fps = next(RATES, fps);
    }

    public void nextSize() {
        size = next(SIZES, size);
    }

    public void nextQuality() {
        VideoEncoder.Quality[] q = VideoEncoder.Quality.values();
        quality = q[(quality.ordinal() + 1) % q.length];
    }

    public String sizeLabel() {
        return size == 0 ? "Game window" : "Up to " + size + "p";
    }

    public String qualityLabel() {
        return switch (quality) { case STANDARD -> "Good"; case HIGH -> "High"; case BEST -> "Best"; };
    }

    /**
     * The screen video's size for a game window of w by h: the window's own,
     * or a smaller height with the window's shape; never larger. Both even.
     */
    public static int[] videoSize(int size, int w, int h) {
        if (size <= 0 || h <= size) return new int[]{Math.max(2, w & ~1), Math.max(2, h & ~1)};
        return ExportSettings.outputSize(size, w, h);
    }

    private static int parse(String s, int fallback) {
        try {
            return s == null ? fallback : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int pick(int[] allowed, int v, int fallback) {
        for (int a : allowed) if (a == v) return v;
        return fallback;
    }

    private static int next(int[] values, int v) {
        for (int i = 0; i < values.length; i++) if (values[i] == v) return values[(i + 1) % values.length];
        return values[0];
    }
}
