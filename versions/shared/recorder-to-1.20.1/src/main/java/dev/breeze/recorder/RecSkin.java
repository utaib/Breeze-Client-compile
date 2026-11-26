package dev.breeze.recorder;

import net.minecraft.client.Minecraft;

/**
 * The player's own skin in a watched recording: up to Minecraft 1.20.1 the
 * profile the game signed in with carries no skin, so nothing is added and
 * the playback draws the default skin. UNVERIFIED as a gap: the owner plays
 * 1.21.11.
 */
final class RecSkin {

    private RecSkin() {}

    static boolean ensure(Minecraft mc) {
        return true;
    }
}
