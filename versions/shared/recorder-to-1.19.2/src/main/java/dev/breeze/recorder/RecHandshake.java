package dev.breeze.recorder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.Connection;

/** The game's own login listener for a watched recording (Minecraft 1.17 to 1.19.2). */
final class RecHandshake {

    private RecHandshake() {}

    static ClientHandshakePacketListenerImpl of(Minecraft mc, Connection conn) {
        return new ClientHandshakePacketListenerImpl(conn, mc, new TitleScreen(), c -> {});
    }
}
