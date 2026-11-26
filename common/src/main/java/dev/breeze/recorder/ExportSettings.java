package dev.breeze.recorder;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * How recordings are exported: the video's size (the game window's, or
 * 720p up to 4K), frame rate (30 to 240, or the rate the player had while
 * recording), quality, and whether the graphics card's encoder may be used.
 * Kept in config/breeze_export.properties in the game folder; changed on
 * the Recordings page.
 *
 * Frames are drawn at the video's size (the game draws into a render target
 * of that size for the export), keeping the window's shape, so 1440p and 4K
 * are drawn at full size rather than scaled up. Sizes are always even, as
 * H.264 needs. Exporting renders on a fixed step, one frame per 1/fps of the
 * recording, so the game's live frame rate never limits the video's.
 */
public final class ExportSettings {

    /** Video sizes by height; 0 is the game window's own size. */
    public static final int[] SIZES = {0, 720, 1080, 1440, 2160};
    /** Frame rates offered; {@link #AS_RECORDED} is the player's own while recording. */
    public static final int[] RATES = {30, 60, 120, 144, 165, 240, 0};
    public static final int AS_RECORDED = 0;
    /** The highest rate "As recorded" gives (a game at 1000 fps still exports at this). */
    public static final int MAX_FPS = 360;
    /** "As recorded" for a recording that does not know its rate (made before 1.2.3). */
    public static final int FALLBACK_FPS = 60;

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

    /** "60 fps", or "As recorded". */
    public String fpsLabel() {
        return fpsLabel(fps);
    }

    public static String fpsLabel(int fps) {
        return fps == AS_RECORDED ? "As recorded" : fps + " fps";
    }

    /**
     * The frame rate a video of this recording gets: the chosen one, or for
     * "As recorded" the player's own rate while recording (rounded, 10 to
     * {@link #MAX_FPS}), {@link #FALLBACK_FPS} when the recording does not
     * say (made before frames were recorded).
     */
    public static int resolveFps(int setting, RecMeta meta) {
        if (setting != AS_RECORDED) return Math.max(1, Math.min(MAX_FPS, setting));
        if (meta == null || meta.fps <= 0 || Double.isNaN(meta.fps)) return FALLBACK_FPS;
        return (int) Math.max(10, Math.min(MAX_FPS, Math.round(meta.fps)));
    }

    /** What an export with these settings makes, for buttons and chat: "1080p, 60 fps". */
    public String summary() {
        return sizeLabel() + ", " + (fps == AS_RECORDED ? "frame rate as recorded" : fps + " fps");
    }

    /** The same for one recording, with "As recorded" as its real rate: "4K, 198 fps (as recorded)". */
    public String summary(RecMeta meta) {
        // A recording still being finished (or made before 1.2.3) does not know its rate yet.
        if (fps != AS_RECORDED || meta == null || meta.fps <= 0) return summary();
        return sizeLabel() + ", " + resolveFps(fps, meta) + " fps (as recorded)";
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
