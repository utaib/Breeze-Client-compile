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
 */
public final class TitleProbe {

    private TitleProbe() {}

    public static TitleScreen make(Minecraft mc) {
        TitleScreen s = new TitleScreen();
        s.init(mc, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
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
