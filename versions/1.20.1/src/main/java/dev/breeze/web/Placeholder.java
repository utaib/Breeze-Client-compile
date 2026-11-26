package dev.breeze.web;

import dev.breeze.ui.Glass;
import dev.breeze.ui.Palette;
import dev.breeze.ui.Rect;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * What the Breeze menu shows for the moment before its page has drawn: the
 * menu's own panel with "Breeze" and a turning ring of dots, drawn by the
 * game, so the player never sees a grey or empty screen (2026-11-20: a grey
 * screen for 6.6 s in a world on a four-core laptop). In a world the game
 * shows through, dimmed, as it does behind the menu; on the title menu the
 * menu's own backdrop colour fills the window.
 */
final class Placeholder {

    private static final int DOTS = 8;
    private static final long TURN_NANOS = 900_000_000L;

    private Placeholder() {}

    static void draw(GuiGraphics g, Font font, int width, int height, boolean ingame, int titleBackdrop) {
        g.fill(0, 0, width, height, ingame ? 0x88000000 : titleBackdrop);
        int w = Math.min(220, width - 32), h = 70;
        if (w < 60) return;
        Rect box = new Rect((width - w) / 2, (height - h) / 2, w, h);
        Glass.panel(g, box);
        int cx = box.x + w / 2;
        g.drawCenteredString(font, "Breeze", cx, box.y + 14, Palette.TEXT_PRIMARY);
        // A ring of dots, the brightest going round once in TURN_NANOS.
        int cy = box.y + 46, r = 9;
        double phase = (System.nanoTime() % TURN_NANOS) / (double) TURN_NANOS * DOTS;
        for (int i = 0; i < DOTS; i++) {
            double a = Math.PI * 2 * i / DOTS - Math.PI / 2;
            int x = cx + (int) Math.round(Math.cos(a) * r), y = cy + (int) Math.round(Math.sin(a) * r);
            double behind = (phase - i + DOTS) % DOTS;
            int alpha = (int) Math.round(255 * Math.max(0.2, 1 - behind / DOTS));
            g.fill(x - 1, y - 1, x + 2, y + 2, Palette.alpha(Palette.ACCENT, alpha));
        }
    }
}
