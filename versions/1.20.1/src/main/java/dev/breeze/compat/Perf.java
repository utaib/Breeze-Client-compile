package dev.breeze.compat;

import net.minecraft.client.Minecraft;

/**
 * The game's own performance figures. Minecraft has a public frame rate
 * getter from 1.19.3; before that only its debug text, so versions/ has a copy
 * of this file per form; this is the 1.19.3 and later form.
 */
public final class Perf {

    private Perf() {}

    /** Frames drawn in the last second, as the debug screen shows. */
    public static int fps(Minecraft mc) {
        return mc.getFps();
    }
}
