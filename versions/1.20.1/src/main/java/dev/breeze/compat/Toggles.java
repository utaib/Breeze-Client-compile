package dev.breeze.compat;

import net.minecraft.client.Minecraft;

/**
 * Minecraft options that Breeze modules switch while they are on. Options
 * became OptionInstance objects in Minecraft 1.19 (plain fields before), so
 * versions/ has a copy of this file per form; this is the 1.19 and later form.
 */
public final class Toggles {

    private Toggles() {}

    public static boolean bobView(Minecraft mc) {
        return mc.options.bobView().get();
    }

    public static void setBobView(Minecraft mc, boolean on) {
        mc.options.bobView().set(on);
    }

    public static boolean subtitles(Minecraft mc) {
        return mc.options.showSubtitles().get();
    }

    public static void setSubtitles(Minecraft mc, boolean on) {
        mc.options.showSubtitles().set(on);
    }
}
