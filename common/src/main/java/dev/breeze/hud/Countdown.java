package dev.breeze.hud;

/**
 * How TNT Countdown writes and sizes a fuse's time (Breeze 1.1.0 directive:
 * "seconds and milliseconds", "distance-based scaling ... clamped"). Pure, so
 * it is tested without Minecraft.
 */
public final class Countdown {

    private Countdown() {}

    /** "3.250s" (seconds and milliseconds), or "3.2s" with {@code tenths}. Never negative. */
    public static String label(double seconds, boolean tenths) {
        double s = Double.isNaN(seconds) || Double.isInfinite(seconds) ? 0 : Math.max(0, seconds);
        if (tenths) return Fmt.d1(s) + "s";
        long ms = Math.round(s * 1000.0);
        long frac = ms % 1000;
        return (ms / 1000) + "." + (frac < 100 ? (frac < 10 ? "00" : "0") : "") + frac + "s";
    }

    /** Distances (blocks) between which a label grows, and how much at most. */
    public static final double NEAR = 8.0, FAR = 24.0, MAX_GROWTH = 3.0;

    /**
     * How large to draw a label at {@code distance} blocks: normal size up to
     * {@link #NEAR}, then growing with the distance to {@link #MAX_GROWTH}
     * times at {@link #FAR} and beyond, so far timers stay readable without
     * nearby ones filling the screen; times the player's size (50 to 200 %).
     */
    public static float size(double distance, boolean grow, int sizePercent) {
        double pct = Math.max(50, Math.min(200, sizePercent)) / 100.0;
        if (!grow || Double.isNaN(distance) || distance <= NEAR) return (float) pct;
        double growth = Math.min(MAX_GROWTH, Math.max(1.0, distance / NEAR));
        return (float) (growth * pct);
    }
}
