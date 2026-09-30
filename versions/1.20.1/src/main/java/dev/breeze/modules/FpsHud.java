package dev.breeze.modules;

import dev.breeze.Category;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

public class FpsHud extends AbstractHudModule {

    public FpsHud() {
        super("FPS", Category.HUD, "Shows current framerate.", KEY_NONE, 4, 4);
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        int fps = mc.getFps();
        int color = fps >= 60 ? 0xFF55FF55 : fps >= 30 ? 0xFFFFFF55 : 0xFFFF5555;
        line(g, font, "FPS: " + fps, color);
    }
}
