package dev.breeze.hud;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

public final class HudHelper {

    private HudHelper() {}

    public static final int WHITE = 0xFFFFFFFF;
    public static final int PANEL_BG = 0x90000000;
    public static final int PANEL_BORDER = 0xFF2B2B2B;

    public static void text(GuiGraphics g, Font font, String s, int x, int y) {
        g.drawString(font, s, x, y, WHITE);
    }

    public static void text(GuiGraphics g, Font font, String s, int x, int y, int color) {
        g.drawString(font, s, x, y, color);
    }

    public static void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, PANEL_BG);
        g.fill(x, y, x + w, y + 1, PANEL_BORDER);
        g.fill(x, y + h - 1, x + w, y + h, PANEL_BORDER);
        g.fill(x, y, x + 1, y + h, PANEL_BORDER);
        g.fill(x + w - 1, y, x + w, y + h, PANEL_BORDER);
    }

    public static int width(Font font, String s) {
        return font.width(s);
    }
}
