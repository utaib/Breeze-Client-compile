package dev.breeze.recorder;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;

/**
 * The tab list's "player joined" packets (Minecraft 1.19.3 and later), which
 * carry each player's skin. They arrive when the world is joined, before any
 * recording starts, so the recorder keeps them for the recording's start:
 * without them the recorded player, the viewer's own included, is drawn
 * with a default skin.
 */
final class RecInfo {

    private RecInfo() {}

    static boolean addsPlayers(Packet<?> p) {
        return p instanceof ClientboundPlayerInfoUpdatePacket u && u.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER);
    }
}
