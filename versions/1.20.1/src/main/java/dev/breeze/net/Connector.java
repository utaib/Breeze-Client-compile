package dev.breeze.net;

import dev.breeze.compat.Compat;
import dev.breeze.BreezeClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

public final class Connector {

    private Connector() {}

    public static void connect(String address) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) {
                mc.level.disconnect();
                Compat.clearLevel(mc);
            }
            BreezeClient.LOGGER.info("[Breeze] join: connecting to {}", address);
            // ServerData's third argument became an enum in 1.20.2, so it is
            // built reflectively rather than with a literal that only compiles
            // on one version.
            ServerData data = (ServerData) Compat.serverData("Breeze World", address);
            ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(address), data, false);
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] join: connect failed: {}", t.toString());
        }
    }
}
