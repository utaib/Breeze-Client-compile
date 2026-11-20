package dev.breeze.recorder;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * How recordings are exported: the video's size (the game window's, or
 * 720p up to 4K), frame rate, quality, and whether the graphics card's
 * encoder may be used. Kept in config/breeze_export.properties in the game
 * folder; changed on the Recordings page.
 *
 * Frames are drawn at the game window's size; a larger video size is
 * reached by scaling each frame (Lanczos) before it is encoded, keeping
 * the picture's shape. Sizes are always even, as H.264 needs.
 */
public final class ExportSettings {

    /** Video sizes by height; 0 is the game window's own size. */
    public static final int[] SIZES = {0, 720, 1080, 1440, 2160};
    public static final int[] RATES = {30, 60, 120};

    public int size = 1080;
    public int fps = 60;
    public VideoEncoder.Quality quality = VideoEncoder.Quality.HIGH;
    /** False: always x264 on the processor. */
    public boolean hardware = true;

    private static ExportSettings current;
    private static Path file;

    /** The settings for this game folder (read once). */
    public static synchronized ExportSettings get(Path gameDir) {
        Path f = gameDir.resolve("config").resolve("breeze_export.properties");
        if (current == null || !f.equals(file)) {
            file = f;
            current = read(f);
        }
        return current;
    }

    static ExportSettings read(Path f) {
        ExportSettings s = new ExportSettings();
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(f)) {
            p.load(in);
        } catch (Exception e) {
            return s;
        }
        s.size = pick(SIZES, parse(p.getProperty("size"), s.size), s.size);
        s.fps = pick(RATES, parse(p.getProperty("fps"), s.fps), s.fps);
        try {
            s.quality = VideoEncoder.Quality.valueOf(p.getProperty("quality", s.quality.name()).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {}
        s.hardware = !"false".equals(p.getProperty("hardware"));
        return s;
    }

    public synchronized void save() {
        if (file == null) return;
        Properties p = new Properties();
        p.setProperty("size", String.valueOf(size));
        p.setProperty("fps", String.valueOf(fps));
        p.setProperty("quality", quality.name().toLowerCase(Locale.ROOT));
        p.setProperty("hardware", String.valueOf(hardware));
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream o = Files.newOutputStream(file)) {
                p.store(o, "Breeze recorder export settings");
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

    public void nextSize() {
        size = next(SIZES, size);
        save();
    }

    public void nextFps() {
        fps = next(RATES, fps);
        save();
    }

    public void nextQuality() {
        VideoEncoder.Quality[] q = VideoEncoder.Quality.values();
        quality = q[(quality.ordinal() + 1) % q.length];
        save();
    }

    public void toggleHardware() {
        hardware = !hardware;
        save();
    }

    public String sizeLabel() {
        return size == 0 ? "Window size" : size == 2160 ? "4K" : size + "p";
    }

    public String qualityLabel() {
        return switch (quality) { case STANDARD -> "Standard"; case HIGH -> "High"; case BEST -> "Best"; };
    }

    /**
     * The video's width and height for frames of w by h: the chosen height
     * with the frames' shape, both even (the window's own size when the
     * setting is 0).
     */
    public static int[] outputSize(int size, int w, int h) {
        if (size <= 0 || h <= 0) return new int[]{w & ~1, h & ~1};
        int oh = size & ~1;
        int ow = (int) Math.round((double) w * oh / h) & ~1;
        return new int[]{Math.max(2, ow), Math.max(2, oh)};
    }
}
