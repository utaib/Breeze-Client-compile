package dev.breeze.compat;

import net.minecraft.client.Minecraft;

/**
 * The game's own performance figures. Minecraft has a public frame rate
 * getter from 1.19.3; before that only its debug text, so versions/ has a copy
 * of this file per form; this is the form before 1.19.3: the number that
 * starts the debug text ("60 fps T: 120 ...").
 */
public final class Perf {

    private Perf() {}

    /** Frames drawn in the last second, as the debug screen shows; 0 before the first second. */
    public static int fps(Minecraft mc) {
        String s = mc.fpsString;
        if (s == null) return 0;
        int end = 0;
        while (end < s.length() && Character.isDigit(s.charAt(end))) end++;
        try {
            return end == 0 ? 0 : Integer.parseInt(s.substring(0, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
