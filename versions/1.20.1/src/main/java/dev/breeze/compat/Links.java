package dev.breeze.compat;

import net.minecraft.Util;

import java.net.URI;

/**
 * Opening a web link in the player's own browser. Minecraft moved this from
 * Util.getPlatform() to Blaze3D in 26.3, so versions/ has a copy of this file
 * per form; this is the form before 26.3.
 */
public final class Links {

    private Links() {}

    /** Opens uri, already checked by the caller, in the system browser. */
    public static void open(URI uri) {
        Util.getPlatform().openUri(uri);
    }
}
