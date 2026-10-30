package dev.breeze.compat;

import net.minecraft.client.Minecraft;

/**
 * Minecraft's debug views that modules switch on. Minecraft 1.21.9 turned
 * them into debug screen entries, so versions/ has a copy of this file per
 * form; this is the form before 1.21.9.
 */
public final class Debug {

    private Debug() {}

    /** Entity hitboxes, as F3+B shows them. */
    public static void setHitboxes(Minecraft mc, boolean shown) {
        mc.getEntityRenderDispatcher().setRenderHitBoxes(shown);
    }
}
