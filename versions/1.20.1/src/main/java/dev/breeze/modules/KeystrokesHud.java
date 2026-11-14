package dev.breeze.modules;

import dev.breeze.Category;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

public class KeystrokesHud extends AbstractHudModule {

    private static final int S = 18;
    private static final int G = 2;

    public KeystrokesHud() {
        super("Keystrokes", Category.HUD, "Shows WASD + jump keys.", KEY_NONE, 4, 410);
    }

    /** Draws its own boxes; sized so the HUD editor's handle and the background frame cover all of them. */
    @Override
    protected boolean drawsShapes() { return true; }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        bounds(3 * S + 2 * G, 3 * S + 2 * G);
        if (mc.player == null) return;
        box(g, font, "W", mc.options.keyUp.isDown(), x + S + G, y, S, S);
        box(g, font, "A", mc.options.keyLeft.isDown(), x, y + S + G, S, S);
        box(g, font, "S", mc.options.keyDown.isDown(), x + S + G, y + S + G, S, S);
        box(g, font, "D", mc.options.keyRight.isDown(), x + 2 * (S + G), y + S + G, S, S);
        box(g, font, "^", mc.options.keyJump.isDown(), x, y + 2 * (S + G), 3 * S + 2 * G, S);
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
