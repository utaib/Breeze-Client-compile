package dev.breeze.modules;

import dev.breeze.Category;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

public class MouseStrokesHud extends AbstractHudModule {

    private static final int S = 22;
    private static final int G = 2;

    public MouseStrokesHud() {
        super("Mouse Strokes", Category.HUD, "Shows mouse buttons.", KEY_NONE, 80, 410);
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        if (mc.player == null) return;
        box(g, font, "LMB", mc.options.keyAttack.isDown(), x, y, S * 2 + G, S);
        box(g, font, "RMB", mc.options.keyUse.isDown(), x, y + S + G, S * 2 + G, S);
    }

    private static void box(GuiGraphics g, Font font, String label, boolean down, int bx, int by, int bw, int bh) {
        g.fill(bx, by, bx + bw, by + bh, down ? 0xCCFFFFFF : 0x80000000);
        int b = 0xFF444444;
        g.fill(bx, by, bx + bw, by + 1, b);
        g.fill(bx, by + bh - 1, bx + bw, by + bh, b);
        g.fill(bx, by, bx + 1, by + bh, b);
        g.fill(bx + bw - 1, by, bx + bw, by + bh, b);
        g.drawCenteredString(font, Component.literal(label), bx + bw / 2, by + (bh - font.lineHeight) / 2 + 1, down ? 0xFF000000 : 0xFFFFFFFF);
    }
}
