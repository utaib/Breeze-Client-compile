package dev.breeze.net;

import dev.breeze.compat.Compat;
import dev.breeze.BreezeClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;

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
            // Both calls differ between Minecraft versions: see Compat.
            ServerData data = Compat.serverData("Breeze World", address);
            Compat.connect(new TitleScreen(), mc, address, data);
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] join: connect failed: {}", t.toString());
        }
    }
}
