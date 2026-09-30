package dev.breeze.compat;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Minecraft's own widgets, as Breeze screens make and read them. Buttons got a
 * builder and widgets position getters in Minecraft 1.19.3, so versions/ has a
 * copy of this file per form; this is the 1.19.3 and later form.
 */
public final class Widgets {

    private Widgets() {}

    /** A plain Minecraft button at (x, y), w by h. */
    public static Button button(Component label, Button.OnPress onPress, int x, int y, int w, int h) {
        return Button.builder(label, onPress).bounds(x, y, w, h).build();
    }

    public static int x(AbstractWidget w) {
        return w.getX();
    }

    public static int y(AbstractWidget w) {
        return w.getY();
    }
}
