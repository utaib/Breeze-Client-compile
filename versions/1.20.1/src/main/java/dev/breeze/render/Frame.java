package dev.breeze.render;

/**
 * How far the current frame is between two client ticks (0 to 1), for
 * drawing moving things smoothly between their tick positions. Measured
 * from the end of the last client tick at 50 ms a tick, the way Minecraft's
 * own timer runs, so it needs no version's timer API.
 */
public final class Frame {

    private static volatile long lastTick = System.nanoTime();

    private Frame() {}

    /** At the end of each client tick (BreezeClient). */
    public static void ticked() {
        lastTick = System.nanoTime();
    }

    public static float partialTick() {
        float pt = (System.nanoTime() - lastTick) / 50_000_000f;
        return pt < 0 ? 0 : Math.min(1, pt);
    }
}
