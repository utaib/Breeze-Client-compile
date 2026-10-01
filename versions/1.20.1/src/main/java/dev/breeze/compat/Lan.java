package dev.breeze.compat;

import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.GameType;

/**
 * Opening the singleplayer world to LAN, which Hosting builds on. The call
 * changed in Minecraft 26.2 (a multiplayer scope) and 26.3 (no game mode), so
 * versions/ has a copy of this file per form; this is the form before 26.2.
 */
public final class Lan {

    private Lan() {}

    /** Opens the world on port with cheats off; true when it opened. */
    public static boolean open(IntegratedServer server, GameType mode, int port) {
        return server.publishServer(mode, false, port);
    }
}
