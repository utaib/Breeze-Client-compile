package dev.breeze.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;

/**
 * The screen Minecraft shows, the loading overlay above it and whether the
 * HUD is hidden. Minecraft 26.2 moved all three from Minecraft and its options
 * to its Gui, so versions/ has a copy of this file per form; this is
 * the form before 26.2.
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

    /** Whether the HUD is hidden, as F1 hides it. */
    public static boolean hudHidden(Minecraft mc) {
        return mc.options.hideGui;
    }

    /** Hides or shows the HUD, as F1 does. */
    public static void setHudHidden(Minecraft mc, boolean hidden) {
        mc.options.hideGui = hidden;
    }
}
