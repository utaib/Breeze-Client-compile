package dev.breeze.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;

/**
 * The screen Minecraft shows, the loading overlay above it and whether the
 * HUD is hidden. Minecraft 26.2 moved all three from Minecraft and its options
 * to its Gui, so versions/ has a copy of this file per form; this is
 * the 26.2 form.
 */
public final class ActiveScreen {

    private ActiveScreen() {}

    /** The open screen, or null in a world with no screen open. */
    public static Screen get(Minecraft mc) {
        return mc.gui.screen();
    }

    /** Opens screen (null closes the current one). */
    public static void set(Minecraft mc, Screen screen) {
        mc.gui.setScreen(screen);
    }

    /** The loading overlay, or null when none is shown. */
    public static Overlay overlay(Minecraft mc) {
        return mc.gui.overlay();
    }

    /** Whether the HUD is hidden, as F1 hides it (26.2: kept by the Hud). */
    public static boolean hudHidden(Minecraft mc) {
        return mc.gui.hud.isHidden();
    }

    /** Hides or shows the HUD, as F1 does (26.2: the Hud only toggles). */
    public static void setHudHidden(Minecraft mc, boolean hidden) {
        if (mc.gui.hud.isHidden() != hidden) mc.gui.hud.toggle();
    }
}
