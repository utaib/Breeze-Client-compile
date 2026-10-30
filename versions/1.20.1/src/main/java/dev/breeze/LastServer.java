package dev.breeze;

import net.minecraft.client.multiplayer.ServerData;

public final class LastServer {

    private static ServerData data;

    private LastServer() {}

    public static void capture(ServerData server) {
        if (server != null) data = server;
    }

    public static ServerData get() {
        return data;
    }
}
