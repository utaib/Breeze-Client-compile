package dev.breeze.modules;

import dev.breeze.Category;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ServerData;

public class ServerAddressHud extends AbstractHudModule {

    public ServerAddressHud() {
        super("Server Address", Category.HUD, "Shows the current server address.", KEY_NONE, 4, 104);
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        String addr;
        ServerData server = mc.getCurrentServer();
        if (mc.isSingleplayer() || server == null) {
            addr = "Singleplayer";
        } else {
            addr = (server.ip == null || server.ip.isEmpty()) ? "Local" : server.ip;
        }
        line(g, font, "Server: " + addr);
    }
}
