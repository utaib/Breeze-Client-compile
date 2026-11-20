package dev.breeze.compat;

import net.minecraft.client.Minecraft;

/**
 * Sending a command (command keys) as a typed one is sent, and whether the
 * server offers it. How a typed command is sent changed in 1.19, 1.19.1 and
 * 1.19.3, so versions/ has a copy of this file per form; this is the form before 1.19: a command is chat starting with a slash.
 */
public final class Commands {

    private Commands() {}

    /** Sends a command, without its slash, as Minecraft sends one typed in chat. Nothing when not connected. */
    public static void send(Minecraft mc, String command) {
        if (mc.player != null) mc.player.chat("/" + command);
    }

    /**
     * Whether the command list the server sent this player has this command:
     * it holds only what the player may use (and Fabric's client commands),
     * so a command missing from it would only be refused.
     */
    public static boolean offered(Minecraft mc, String name) {
        if (mc.getConnection() == null || name == null || name.isEmpty()) return false;
        var root = mc.getConnection().getCommands().getRoot();
        return root.getChild(name) != null;
    }
}
