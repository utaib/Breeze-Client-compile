package dev.breeze.compat;

import net.minecraft.client.Minecraft;

/**
 * Sending a chat line as the player. Chat signing changed how in 1.19, 1.19.1
 * and 1.19.3, so versions/ has a copy of this file per form; this is the
 * form before 1.19.1: LocalPlayer.chat, which signs it when it can.
 */
public final class Chat {

    private Chat() {}

    /** Sends text as the player's own chat message; nothing when not in a world. */
    public static void send(Minecraft mc, String text) {
        if (mc.player != null) mc.player.chat(text);
    }
}
