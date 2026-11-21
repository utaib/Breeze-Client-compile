package dev.breeze.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.function.Predicate;

/**
 * How long a menu took from the key to its first drawn frame. Something
 * names what it waits for ({@link #expect}); after each frame
 * (RecorderHooks.frame, the tail of the game's frame) the first frame
 * whose screen passes the test ends the wait. Used by the self-test for the
 * Breeze menu, the game menu and the way back; one volatile read a frame
 * when nothing waits.
 */
public final class OpenTimer {

    /** Told each result: what, milliseconds, the screen's kind. */
    public static volatile java.util.function.BiConsumer<String, Long> onResult;

    private static volatile String what;
    private static volatile Predicate<Screen> test;
    private static volatile long since;
    private static volatile long result = -1;

    private OpenTimer() {}

    /** Waits for the first frame whose screen passes test, timed from startNanos. */
    public static void expect(String name, long startNanos, Predicate<Screen> screen) {
        result = -1;
        since = startNanos;
        test = screen;
        what = name;
    }

    /** The last result in milliseconds, or -1 while waiting (or never asked). */
    public static long result() {
        return result;
    }

    public static boolean waiting() {
        return what != null;
    }

    public static void cancel() {
        what = null;
    }

    /** Frames drawn so far (the self-test's frame rate with and without a hidden browser). */
    public static volatile long frames;

    /** After each frame. */
    public static void frame(Minecraft mc) {
        frames++;
        String w = what;
        if (w == null) return;
        Predicate<Screen> t = test;
        if (t == null || !t.test(dev.breeze.compat.ActiveScreen.get(mc))) return;
        long ms = (System.nanoTime() - since) / 1_000_000L;
        what = null;
        result = ms;
        java.util.function.BiConsumer<String, Long> c = onResult;
        if (c != null) c.accept(w, ms);
    }
}
