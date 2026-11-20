package dev.breeze.devtest;

/**
 * The native menu's frame cost, measured by the self-test: the menu's shapes
 * drawn as 2.24.0 drew them ({@link LegacyShapes}), then as they are drawn
 * now, on the same machine one after the other. Testers found the native
 * menus slow on 26.1 and other versions while the game itself was fine
 * (2026-11-08); this is what shows the fix in numbers rather than by eye.
 *
 * Only the self-test turns {@link #legacy} on; for players it stays false and
 * {@link #frame} returns at once.
 */
public final class MenuPerf {

    private MenuPerf() {}

    /** True only while the self-test measures the 2.24.0 shapes. */
    public static boolean legacy;

    /** Rectangles the menu's rounded shapes cost this frame (counted by Glass and LegacyShapes). */
    public static int rects;

    private static final long WARM_NS = 1_500_000_000L;
    private static final long MEASURE_NS = 4_000_000_000L;

    private static int phase; // 0 waiting, 1 old warm-up, 2 old measured, 3 new warm-up, 4 new measured, 5 done
    private static long phaseStart;
    private static long renderNanos;
    private static long rectSum;
    private static int frames;
    private static final String[] OLD = new String[3];

    /** At the end of the native menu's render: how long its drawing took. */
    public static void frame(long nanos) {
        int r = rects;
        rects = 0;
        if (phase == 5 || !AutoTest.running()) return;
        long now = System.nanoTime();
        if (phase == 0) {
            legacy = true;
            next(1, now);
            return;
        }
        long age = now - phaseStart;
        if (phase == 1 || phase == 3) {
            if (age >= WARM_NS) next(phase + 1, now);
            return;
        }
        renderNanos += nanos;
        rectSum += r;
        frames++;
        if (age < MEASURE_NS) return;
        double seconds = age / 1e9;
        String fps = fmt(frames / seconds);
        String ms = fmt(renderNanos / 1e6 / Math.max(1, frames));
        String perFrame = String.valueOf(rectSum / Math.max(1, frames));
        if (phase == 2) {
            OLD[0] = fps;
            OLD[1] = ms;
            OLD[2] = perFrame;
            legacy = false;
            next(3, now);
            return;
        }
        AutoTest.report("native-menu-perf",
                "oldFps", OLD[0], "oldRenderMs", OLD[1], "oldRects", OLD[2],
                "fps", fps, "renderMs", ms, "rects", perFrame);
        phase = 5;
    }

    private static void next(int p, long now) {
        phase = p;
        phaseStart = now;
        renderNanos = 0;
        rectSum = 0;
        frames = 0;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
