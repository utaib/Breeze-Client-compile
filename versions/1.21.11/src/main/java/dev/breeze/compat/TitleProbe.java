package dev.breeze.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

/**
 * Minecraft's title screen, laid out off screen the way Minecraft lays it out
 * when it shows it, so other mods add their buttons to it (Replay Mod's Replay
 * Viewer, Flashback's replays). Breeze's web menu stands in for the title
 * screen, so those buttons never appeared; Integrations offers them in the
 * Breeze menus instead and presses them on a screen made here.
 *
 * From 1.21.11 a screen knows its Minecraft from the start and init takes
 * only the size; this is that form.
 */
public final class TitleProbe {

    private TitleProbe() {}

    public static TitleScreen make(Minecraft mc) {
        TitleScreen s = new TitleScreen();
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        s.init(w, h);
        // Fabric's screen events, which Replay Mod and others use to add their
        // buttons, fire when a screen is shown or resized, not on init alone;
        // Flashback hooks the title screen itself. A resize lays the screen
        // out again with those events.
        s.resize(w, h);
        return s;
    }

    /** What Minecraft does when a screen goes away (title screen: stops its Realms checks). */
    public static void dispose(Screen s) {
        try {
            s.removed();
        } catch (Throwable ignored) {
        }
    }
}
