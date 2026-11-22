package dev.breeze.compat;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Minecraft's own widgets, as Breeze screens make and read them. Buttons got a
 * builder and widgets position getters in Minecraft 1.19.3, so versions/ has a
 * copy of this file per form; this is the 1.21.9 and later form (onPress takes
 * the input that pressed it).
 */
public final class Widgets {

    private Widgets() {}

    /** A plain Minecraft button at (x, y), w by h. */
    public static Button button(Component label, Button.OnPress onPress, int x, int y, int w, int h) {
        return Button.builder(label, onPress).bounds(x, y, w, h).build();
    }

    /** Grey text an empty field shows. */
    public static void hint(net.minecraft.client.gui.components.EditBox box, Component hint) {
        box.setHint(hint);
    }

    public static int x(AbstractWidget w) {
        return w.getX();
    }

    public static int y(AbstractWidget w) {
        return w.getY();
    }

    /**
     * Presses a button as a click would (its own action, sound aside). Used
     * for other mods' title screen buttons, which Breeze offers in its menus
     * (Integrations): Minecraft 1.21.9 gave onPress an input argument, so
     * versions/1.21.9 has a copy of this file with that form.
     */
    public static void press(net.minecraft.client.gui.components.AbstractButton b) {
        b.onPress(new net.minecraft.client.input.KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN, 0, 0));
    }
}
