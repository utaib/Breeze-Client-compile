package dev.breeze.input;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Recent mouse clicks, for anything that wants a rate rather than a state.
 *
 * Kept separate from the modules that read it so the mixin has one small thing
 * to talk to, and so a second consumer does not have to reach into a HUD class.
 *
 * Only the last second matters to every caller so far, and old entries are
 * dropped on both write and read. That is what keeps these bounded without a
 * tick hook: a player who clicks and then stops leaves at most a second of
 * timestamps behind, and nothing accumulates while the game sits in a menu.
 */
public final class Clicks {

    /** GLFW's left and right button ids. */
    public static final int LEFT = 0;
    public static final int RIGHT = 1;

    private static final long WINDOW_MS = 1000;
    /** A click rate no human produces; the cap only exists so a stuck autoclicker cannot grow these without bound. */
    private static final int MAX_TRACKED = 64;

    private static final Deque<Long> left = new ArrayDeque<>();
    private static final Deque<Long> right = new ArrayDeque<>();

    private Clicks() {}

    /** Called from the mouse mixin on the client thread. */
    public static void onPress(int button) {
        long now = System.currentTimeMillis();
        if (button == LEFT) record(left, now);
        else if (button == RIGHT) record(right, now);
    }

    /** Clicks in the last second. */
    public static int perSecond(int button) {
        Deque<Long> q = button == RIGHT ? right : left;
        trim(q, System.currentTimeMillis());
        return q.size();
    }

    private static void record(Deque<Long> q, long now) {
        trim(q, now);
        if (q.size() < MAX_TRACKED) q.addLast(now);
    }

    private static void trim(Deque<Long> q, long now) {
        while (!q.isEmpty() && now - q.peekFirst() > WINDOW_MS) q.pollFirst();
    }
}
