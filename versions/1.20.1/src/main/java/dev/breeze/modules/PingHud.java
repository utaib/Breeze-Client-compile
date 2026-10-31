package dev.breeze.modules;

import dev.breeze.Category;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;

public class PingHud extends AbstractHudModule {

    public PingHud() {
        super("Ping", Category.HUD, "Shows server latency.", KEY_NONE, 4, 44);
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        if (mc.player == null || mc.getConnection() == null) return;
        PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        if (info == null) {
            line(g, font, "Ping: --");
            return;
        }
        int ping = info.getLatency();
        int color = ping < 100 ? 0xFF55FF55 : ping < 250 ? 0xFFFFFF55 : 0xFFFF5555;
        line(g, font, "Ping: " + ping + "ms", color);
    }
}
