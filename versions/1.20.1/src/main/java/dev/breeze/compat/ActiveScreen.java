package dev.breeze.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;

/**
 * The screen Minecraft shows, and the loading overlay above it. Minecraft
 * 26.2 moved both from Minecraft to its Gui, so versions/ has a copy of this
 * file per form; this is the form before 26.2.
 */
public final class ActiveScreen {

    private ActiveScreen() {}

    /** The open screen, or null in a world with no screen open. */
    public static Screen get(Minecraft mc) {
        return mc.screen;
    }

    /** Opens screen (null closes the current one). */
    public static void set(Minecraft mc, Screen screen) {
        mc.setScreen(screen);
    }

    /** The loading overlay, or null when none is shown. */
    public static Overlay overlay(Minecraft mc) {
        return mc.getOverlay();
    }
}
