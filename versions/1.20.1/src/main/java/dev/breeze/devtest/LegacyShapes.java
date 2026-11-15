package dev.breeze.devtest;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Glass's rounded fill and outline exactly as 2.24.0 drew them: one fill per
 * scanline of each corner, one per point of a two-degree arc, each drawn on
 * its own. Kept only so the self-test can measure the native menu with them
 * and without them in the same run ({@link MenuPerf}); players never reach it.
 */
public final class LegacyShapes {

    private LegacyShapes() {}

    public static void fillRounded(GuiGraphics g, int x1, int y1, int x2, int y2, int r, int color) {
        int w = x2 - x1, h = y2 - y1;
        if (w <= 0 || h <= 0) return;
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        if (r == 0) { fill(g, x1, y1, x2, y2, color); return; }

        fill(g, x1 + r, y1, x2 - r, y2, color);
        fill(g, x1, y1 + r, x1 + r, y2 - r, color);
        fill(g, x2 - r, y1 + r, x2, y2 - r, color);

        quadrant(g, x1 + r, y1 + r, r, 0, color);
        quadrant(g, x2 - r, y1 + r, r, 1, color);
        quadrant(g, x2 - r, y2 - r, r, 2, color);
        quadrant(g, x1 + r, y2 - r, r, 3, color);
    }

    private static void quadrant(GuiGraphics g, int cx, int cy, int r, int q, int color) {
        for (int dy = 0; dy <= r; dy++) {
            int dx = (int) Math.sqrt((double) (r * r - dy * dy));
            switch (q) {
                case 0 -> fill(g, cx - dx, cy - dy, cx, cy - dy + 1, color);
                case 1 -> fill(g, cx, cy - dy, cx + dx, cy - dy + 1, color);
                case 2 -> fill(g, cx, cy + dy, cx + dx, cy + dy + 1, color);
                default -> fill(g, cx - dx, cy + dy, cx, cy + dy + 1, color);
            }
        }
    }

    public static void roundedBorder(GuiGraphics g, int x1, int y1, int x2, int y2, int r, int color) {
        int w = x2 - x1, h = y2 - y1;
        if (w <= 0 || h <= 0) return;
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));

        fill(g, x1 + r, y1, x2 - r, y1 + 1, color);
        fill(g, x1 + r, y2 - 1, x2 - r, y2, color);
        fill(g, x1, y1 + r, x1 + 1, y2 - r, color);
        fill(g, x2 - 1, y1 + r, x2, y2 - r, color);

        if (r > 0) {
            arc(g, x1 + r, y1 + r, r, 180, 270, color);
            arc(g, x2 - r, y1 + r, r, 270, 360, color);
            arc(g, x2 - r, y2 - r, r, 0, 90, color);
            arc(g, x1 + r, y2 - r, r, 90, 180, color);
        }
    }

    private static void arc(GuiGraphics g, int cx, int cy, int r, int from, int to, int color) {
        for (int a = from; a <= to; a += 2) {
            double rad = Math.toRadians(a);
            int px = cx + (int) Math.round(Math.cos(rad) * r);
            int py = cy + (int) Math.round(Math.sin(rad) * r);
            fill(g, px, py, px + 1, py + 1, color);
        }
    }

    private static void fill(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        MenuPerf.rects++;
        g.fill(x1, y1, x2, y2, color);
    }
}
