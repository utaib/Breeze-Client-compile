package dev.breeze.modules;

import dev.breeze.Category;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

public class RecordingIndicatorHud extends AbstractHudModule {

    public RecordingIndicatorHud() {
        super("Recording Indicator", Category.HUD, "Shows a recording indicator.", KEY_NONE, 0, 0);
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        String s = "● REC";
        int w = g.guiWidth();
        g.drawString(font, s, w - font.width(s) - 6, 6, 0xFFFF3333);
    }
}
