package dev.breeze.recorder;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoPacket;

/**
 * The tab list's "player joined" packets (Minecraft 1.17 to 1.19.2), which
 * carry each player's skin. They arrive when the world is joined, before any
 * recording starts, so the recorder keeps them for the recording's start:
 * without them the recorded player, the viewer's own included, is drawn
 * with a default skin.
 */
final class RecInfo {

    private RecInfo() {}

    static boolean addsPlayers(Packet<?> p) {
        return p instanceof ClientboundPlayerInfoPacket u && u.getAction() == ClientboundPlayerInfoPacket.Action.ADD_PLAYER;
    }
}
