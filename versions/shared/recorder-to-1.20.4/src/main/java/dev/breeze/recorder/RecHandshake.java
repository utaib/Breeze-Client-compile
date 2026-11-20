package dev.breeze.recorder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.Connection;

/** The game's own login listener for a watched recording (Minecraft 1.19.3 to 1.20.4). */
final class RecHandshake {

    private RecHandshake() {}

    static ClientHandshakePacketListenerImpl of(Minecraft mc, Connection conn) {
        return new ClientHandshakePacketListenerImpl(conn, mc, null, new TitleScreen(), false, java.time.Duration.ZERO, c -> {});
    }
}
