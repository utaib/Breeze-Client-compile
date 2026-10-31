package dev.breeze.compat;

import net.minecraft.client.Minecraft;

/**
 * Minecraft options that Breeze modules switch while they are on. Options
 * became OptionInstance objects in Minecraft 1.19 (plain fields before), so
 * versions/ has a copy of this file per form; this is the form before 1.19.
 */
public final class Toggles {

    private Toggles() {}

    public static boolean bobView(Minecraft mc) {
        return mc.options.bobView;
    }

    public static void setBobView(Minecraft mc, boolean on) {
        mc.options.bobView = on;
    }

    public static boolean subtitles(Minecraft mc) {
        return mc.options.showSubtitles;
    }

    public static void setSubtitles(Minecraft mc, boolean on) {
        mc.options.showSubtitles = on;
    }
}
