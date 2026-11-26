package dev.breeze.recorder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.Connection;

/** The game's own login listener for a watched recording (Minecraft 1.21.9 and later). */
final class RecHandshake {

    private RecHandshake() {}

    static ClientHandshakePacketListenerImpl of(Minecraft mc, Connection conn) {
        return new ClientHandshakePacketListenerImpl(conn, mc, null, new TitleScreen(), false, java.time.Duration.ZERO, c -> {}, new net.minecraft.client.multiplayer.LevelLoadTracker(), null);
    }
}
