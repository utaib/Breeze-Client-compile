package dev.breeze.modules;

import dev.breeze.Category;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

public class PlaytimeHud extends AbstractHudModule {

    private long start;

    public PlaytimeHud() {
        super("Playtime", Category.HUD, "Shows session playtime.", KEY_NONE, 4, 144);
    }

    @Override
    protected void onEnable() {
        start = System.currentTimeMillis();
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        line(g, font, "Playtime: " + format(System.currentTimeMillis() - start));
    }

    static String format(long ms) {
        long s = ms / 1000;
        return String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }
}
