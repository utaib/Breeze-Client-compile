package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.LastServer;
import dev.breeze.Module;
import net.minecraft.client.Minecraft;
import dev.breeze.compat.Compat;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;

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
                Compat.connect(new TitleScreen(), mc, last.ip, last);
            }
        } catch (Throwable ignored) {}
        setStateSilently(false);
    }
}
