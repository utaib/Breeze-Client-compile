package dev.breeze.compat;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Minecraft's own widgets, as Breeze screens make and read them. Buttons got a
 * builder and widgets position getters in Minecraft 1.19.3, so versions/ has a
 * copy of this file per form; this is the form before 1.19.3: a constructor,
 * and public x and y fields.
 */
public final class Widgets {

    private Widgets() {}

    /** A plain Minecraft button at (x, y), w by h. */
    public static Button button(Component label, Button.OnPress onPress, int x, int y, int w, int h) {
        return new Button(x, y, w, h, label, onPress);
    }

    public static int x(AbstractWidget w) {
        return w.x;
    }

    public static int y(AbstractWidget w) {
        return w.y;
    }
}
