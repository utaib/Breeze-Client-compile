package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.LastServer;
import dev.breeze.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

public class Reconnect extends Module {

    public Reconnect() {
        super("Reconnect", Category.UTILITY, "Reconnects to the last server.", KEY_NONE);
    }

    @Override
    protected void onEnable() {
        try {
            Minecraft mc = Minecraft.getInstance();
            ServerData last = LastServer.get();
            if (last != null) {
                ServerAddress address = ServerAddress.parseString(last.ip);
                ConnectScreen.startConnecting(new TitleScreen(), mc, address, last, false);
            }
        } catch (Throwable ignored) {}
        setStateSilently(false);
    }
}
