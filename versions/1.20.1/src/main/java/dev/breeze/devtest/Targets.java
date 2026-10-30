package dev.breeze.devtest;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Named click points that Breeze's own screens publish for the in-game
 * self-test, in GUI units. Minecraft's widgets are found by their labels, but
 * Breeze draws some controls itself (the title screen's Breeze button, the
 * native menu's module cards), and those have no widget to find. Publishing
 * them here lets the driver click by name instead of by pixel positions that
 * shift between versions and window sizes.
 *
 * Does nothing unless the self-test is on (see {@link AutoTest}).
 */
public final class Targets {

    private static volatile boolean enabled;
    private static final Map<String, int[]> POINTS = new LinkedHashMap<>();

    private Targets() {}

    static void enable() {
        enabled = true;
    }

    /** Called from render: the centre of a control, in GUI units. */
    public static void put(String name, int guiX, int guiY) {
        if (!enabled) return;
        synchronized (POINTS) {
            POINTS.put(name, new int[] {guiX, guiY});
        }
    }

    static Map<String, int[]> snapshot() {
        synchronized (POINTS) {
            return new LinkedHashMap<>(POINTS);
        }
    }

    /** Points belong to the screen that drew them; a new screen starts empty. */
    static void clear() {
        synchronized (POINTS) {
            POINTS.clear();
        }
    }
}
