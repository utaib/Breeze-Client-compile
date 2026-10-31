package dev.breeze.compat;

import net.minecraft.client.Minecraft;

/**
 * Sending a chat line as the player. Chat signing changed how in 1.19, 1.19.1
 * and 1.19.3, so versions/ has a copy of this file per form; this is the 1.19.3
 * and later form.
 */
public final class Chat {

    private Chat() {}

    /** Sends text as the player's own chat message; nothing when not connected. */
    public static void send(Minecraft mc, String text) {
        if (mc.getConnection() != null) mc.getConnection().sendChat(text);
    }
}
