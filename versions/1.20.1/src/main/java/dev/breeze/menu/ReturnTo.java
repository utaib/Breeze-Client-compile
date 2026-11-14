package dev.breeze.menu;

import net.minecraft.client.gui.screens.Screen;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Where a vanilla screen Breeze opened goes back to on Escape. Minecraft's
 * Options screen returns to the screen that opened it only from its Done
 * button on older versions (1.17's Escape went to the title screen), so
 * Escape from the options Breeze's settings opened left Breeze. Done already
 * returns there; this makes Escape agree ({@code ScreenCloseMixin}).
 */
public final class ReturnTo {

    private static final Map<Screen, Screen> PARENTS = new WeakHashMap<>();

    private ReturnTo() {}

    /** Opens {@code screen} from {@code parent}, and remembers where it goes back to. */
    public static Screen from(Screen parent, Screen screen) {
        synchronized (PARENTS) {
            PARENTS.put(screen, parent);
        }
        return screen;
    }

    /** The screen {@code closing} goes back to on Escape, once; null if none. */
    public static Screen take(Screen closing) {
        synchronized (PARENTS) {
            return PARENTS.remove(closing);
        }
    }
}
