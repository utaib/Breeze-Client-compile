package dev.breeze.recorder;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * What the Recorder key records (the player's preferences, asked for on the
 * first press and changeable later): the screen video is always made; the
 * world replay (to watch again and render with other packs) is kept too
 * unless switched off. Frame rate, size, quality and whether the graphics
 * card's encoder may be used apply to the screen video. Kept in
 * config/breeze_capture.properties in the game folder.
 */
public final class CaptureSettings {

    public static final int[] RATES = {30, 60, 120};
    /** Video heights; 0 is the game window's own size. */
    public static final int[] SIZES = {0, 1080, 720, 480};

    /** False until the player saved the preferences once (the first press asks). */
    public boolean configured;
    public boolean keepReplay = true;
    public int fps = 60;
    public int size = 0;
    public VideoEncoder.Quality quality = VideoEncoder.Quality.HIGH;
    public boolean hardware = true;
    /** The game's own sound as its own track (recorded by the Breeze launcher; Windows 10 2004 and later). */
    public boolean gameSound = true;
    /** The microphone as its own track. */
    public boolean mic = false;
    /** The microphone's name as Java lists it; empty for the system's default input. */
    public String micDevice = "";

    private static CaptureSettings current;
    private static Path file;

    public static synchronized CaptureSettings get(Path gameDir) {
        Path f = gameDir.resolve("config").resolve("breeze_capture.properties");
        if (current == null || !f.equals(file)) {
            file = f;
            current = read(f);
        }
        return current;
    }

    static CaptureSettings read(Path f) {
        CaptureSettings s = new CaptureSettings();
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(f)) {
            p.load(in);
        } catch (Exception e) {
            return s;
        }
        s.configured = "true".equals(p.getProperty("configured"));
        s.keepReplay = !"false".equals(p.getProperty("keepReplay"));
        s.fps = pick(RATES, parse(p.getProperty("fps"), s.fps), s.fps);
        s.size = pick(SIZES, parse(p.getProperty("size"), s.size), s.size);
        try {
            s.quality = VideoEncoder.Quality.valueOf(p.getProperty("quality", s.quality.name()).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {}
        s.hardware = !"false".equals(p.getProperty("hardware"));
        s.gameSound = !"false".equals(p.getProperty("gameSound"));
        s.mic = "true".equals(p.getProperty("mic"));
        s.micDevice = p.getProperty("micDevice", "").trim();
        return s;
    }

    public synchronized void save() {
        if (file == null) return;
        Properties p = new Properties();
        p.setProperty("configured", String.valueOf(configured));
        p.setProperty("keepReplay", String.valueOf(keepReplay));
        p.setProperty("fps", String.valueOf(fps));
        p.setProperty("size", String.valueOf(size));
        p.setProperty("quality", quality.name().toLowerCase(Locale.ROOT));
        p.setProperty("hardware", String.valueOf(hardware));
        p.setProperty("gameSound", String.valueOf(gameSound));
        p.setProperty("mic", String.valueOf(mic));
        p.setProperty("micDevice", micDevice == null ? "" : micDevice);
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream o = Files.newOutputStream(file)) {
                p.store(o, "Breeze recorder preferences");
            }
        } catch (Exception ignored) {
            // Not saved: the choice still holds until the game closes.
        }
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
        return size == 0 ? "Game window" : size + "p";
    }

    public String qualityLabel() {
        return switch (quality) { case STANDARD -> "Standard"; case HIGH -> "High"; case BEST -> "Best"; };
    }

    /**
     * The video's size for a window of w by h: the window's own (even), or
     * the chosen height with the window's shape, never larger than the
     * window (a smaller video is scaled down on the graphics card).
     */
    public static int[] videoSize(int size, int w, int h) {
        if (size <= 0 || h <= 0 || size >= h) return new int[]{Math.max(2, w & ~1), Math.max(2, h & ~1)};
        return ExportSettings.outputSize(size, w, h);
    }
}
