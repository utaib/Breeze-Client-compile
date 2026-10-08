package dev.breeze.ui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Glassmorphic drawing primitives: genuinely round corners, soft borders,
 * gradients and glass panels.
 *
 * Ported from BREEZE_INGAME_UI.md. That document is written against Yarn
 * mappings (DrawContext, MinecraftClient, Identifier); this mod builds against
 * Mojang official mappings, so every call is translated:
 *
 *   DrawContext      -> GuiGraphics
 *   ctx.fill         -> g.fill
 *   ctx.fillGradient -> g.fillGradient
 *   MinecraftClient  -> Minecraft
 *   mc.textRenderer  -> mc.font
 *
 * Why this replaces {@link UiRender#rounded}: that method fakes a radius by
 * insetting three rectangles by one pixel, which reads as a chamfer rather than
 * a curve and is the main reason the client still looked like vanilla
 * Minecraft's square widgets. These corners are real quarter-circles.
 *
 * Cost: a rounded rect costs about 4r extra fills (r = radius). At r=6 that is
 * roughly 24 additional draws per panel. Fine for menus, which is where it is
 * used. Do NOT use these on a per-frame HUD path, where {@link UiRender} still
 * has the cheap rectangular versions.
 */
public final class Glass {

    private Glass() {}

    /** Standard corner radius. Large enough to read as round at GUI scale 2+. */
    public static final int RADIUS = 6;
    /** Tighter radius for small controls: rows, chips, buttons. */
    public static final int RADIUS_SM = 4;

    // ── Filled shapes ───────────────────────────────────────────────────────

    /**
     * A filled rectangle with true rounded corners.
     *
     * The body is drawn as three rectangles (a full-height centre column plus
     * two shorter side columns) and the four corners as quarter-circles, so the
     * curve is actually circular rather than a stepped approximation.
     */
    public static void fillRounded(GuiGraphics g, int x1, int y1, int x2, int y2, int r, int color) {
        int w = x2 - x1, h = y2 - y1;
        if (w <= 0 || h <= 0) return;
        // A radius larger than half the smaller side would make the corners
        // overlap and paint outside the shape.
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        if (r == 0) { g.fill(x1, y1, x2, y2, color); return; }

        g.fill(x1 + r, y1, x2 - r, y2, color);
        g.fill(x1, y1 + r, x1 + r, y2 - r, color);
        g.fill(x2 - r, y1 + r, x2, y2 - r, color);

        quadrant(g, x1 + r, y1 + r, r, 0, color);
        quadrant(g, x2 - r, y1 + r, r, 1, color);
        quadrant(g, x2 - r, y2 - r, r, 2, color);
        quadrant(g, x1 + r, y2 - r, r, 3, color);
    }

    /** One quarter-circle, filled by horizontal scanlines. 0=TL 1=TR 2=BR 3=BL. */
    private static void quadrant(GuiGraphics g, int cx, int cy, int r, int q, int color) {
        for (int dy = 0; dy <= r; dy++) {
            int dx = (int) Math.sqrt((double) (r * r - dy * dy));
            switch (q) {
                case 0 -> g.fill(cx - dx, cy - dy, cx, cy - dy + 1, color);
                case 1 -> g.fill(cx, cy - dy, cx + dx, cy - dy + 1, color);
                case 2 -> g.fill(cx, cy + dy, cx + dx, cy + dy + 1, color);
                default -> g.fill(cx - dx, cy + dy, cx, cy + dy + 1, color);
            }
        }
    }

    // ── Borders ─────────────────────────────────────────────────────────────

    /** A 1px outline that follows the same curve as {@link #fillRounded}. */
    public static void roundedBorder(GuiGraphics g, int x1, int y1, int x2, int y2, int r, int color) {
        int w = x2 - x1, h = y2 - y1;
        if (w <= 0 || h <= 0) return;
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));

        g.fill(x1 + r, y1, x2 - r, y1 + 1, color);
        g.fill(x1 + r, y2 - 1, x2 - r, y2, color);
        g.fill(x1, y1 + r, x1 + 1, y2 - r, color);
        g.fill(x2 - 1, y1 + r, x2, y2 - r, color);

        if (r > 0) {
            arc(g, x1 + r, y1 + r, r, 180, 270, color);
            arc(g, x2 - r, y1 + r, r, 270, 360, color);
            arc(g, x2 - r, y2 - r, r, 0, 90, color);
            arc(g, x1 + r, y2 - r, r, 90, 180, color);
        }
    }

    /**
     * A single-pixel arc.
     *
     * Stepped by 2 degrees rather than the 3 in the source document: at radius 6
     * a 3-degree step leaves visible gaps in the curve, because consecutive
     * points can round to the same pixel and then skip one.
     */
    private static void arc(GuiGraphics g, int cx, int cy, int r, int from, int to, int color) {
        for (int a = from; a <= to; a += 2) {
            double rad = Math.toRadians(a);
            int px = cx + (int) Math.round(Math.cos(rad) * r);
            int py = cy + (int) Math.round(Math.sin(rad) * r);
            g.fill(px, py, px + 1, py + 1, color);
        }
    }

    // ── Panels ──────────────────────────────────────────────────────────────

    /**
     * A glass panel: shadow, tinted fill, hairline border, accent top edge.
     *
     * This is the signature Breeze surface and should be what every menu panel
     * uses. The accent gradient along the top edge is the detail that most
     * separates it from a plain box: it fades in and out from the centre, which
     * a rectangle border cannot do.
     */
    public static void panel(GuiGraphics g, Rect box) {
        panel(g, box, Palette.SURFACE, Palette.BORDER);
    }

    public static void panel(GuiGraphics g, Rect box, int fill, int border) {
        if (box.isEmpty()) return;
        int x1 = box.x, y1 = box.y, x2 = box.right(), y2 = box.bottom();

        // Soft drop shadow: stacked translucent rounded rects, growing outward
        // and fading. Minecraft's GUI layer has no cheap blur, and four extra
        // shapes cost far less than a shader pass.
        for (int i = 4; i >= 1; i--) {
            int a = 0x16 - i * 4;
            if (a <= 0) continue;
            fillRounded(g, x1 - i, y1 - i + 2, x2 + i, y2 + i + 2,
                    RADIUS + i, Palette.alpha(Palette.SHADOW, a));
        }

        fillRounded(g, x1, y1, x2, y2, RADIUS, fill);
        roundedBorder(g, x1, y1, x2, y2, RADIUS, border);
        accentEdge(g, x1 + RADIUS, y1 + 1, x2 - RADIUS);
    }

    /**
     * The accent hairline along a panel's top edge, brightest at the centre.
     *
     * Drawn as two gradients meeting in the middle so it fades at both ends
     * instead of stopping abruptly.
     */
    public static void accentEdge(GuiGraphics g, int x1, int y, int x2) {
        if (x2 <= x1) return;
        int mid = (x1 + x2) / 2;
        int edge = Palette.alpha(Palette.ACCENT, 0x00);
        int peak = Palette.alpha(Palette.ACCENT, 0x55);
        g.fillGradient(x1, y, mid, y + 1, edge, peak);
        g.fillGradient(mid, y, x2, y + 1, peak, edge);
    }

    // ── Controls ────────────────────────────────────────────────────────────

    /** A rounded row/chip that responds to hover and selection. */
    public static void surface(GuiGraphics g, Rect box, boolean hovered, boolean active) {
        if (box.isEmpty()) return;
        fillRounded(g, box.x, box.y, box.right(), box.bottom(), RADIUS_SM,
                active ? Palette.ACCENT_DIM : Palette.surfaceFor(hovered, false));
        roundedBorder(g, box.x, box.y, box.right(), box.bottom(), RADIUS_SM,
                (hovered || active) ? Palette.BORDER_HOVER : Palette.BORDER);
    }

    /** A pill-shaped toggle. Fully round ends, so radius is half the height. */
    public static void toggle(GuiGraphics g, int x, int y, int w, int h, boolean on) {
        int r = h / 2;
        fillRounded(g, x, y, x + w, y + h, r, on ? Palette.ACCENT : Palette.SURFACE_ACTIVE);
        roundedBorder(g, x, y, x + w, y + h, r, on ? Palette.BORDER_HOVER : Palette.BORDER);
        int knob = h - 4;
        int kx = on ? x + w - knob - 2 : x + 2;
        fillRounded(g, kx, y + 2, kx + knob, y + 2 + knob, knob / 2, Palette.TEXT_PRIMARY);
    }

    /** A small rounded label, for "role", "animated", rarity and similar. */
    public static void badge(GuiGraphics g, net.minecraft.client.gui.Font font,
                             String label, int x, int y, int bg, int fg) {
        int w = font.width(label) + 8;
        fillRounded(g, x, y, x + w, y + 11, 3, bg);
        g.drawString(font, label, x + 4, y + 2, fg, false);
    }

    /**
     * Full-screen scrim drawn behind a menu.
     *
     * Approach B from the source document. Approach A (a real Gaussian blur
     * post-effect) is deliberately not used: it needs a shader JSON, a
     * PostEffectProcessor whose constructor signature changes between Minecraft
     * versions, and framebuffer management that has to be undone on close. On
     * the integrated GPUs this client targets, a full-screen blur pass every
     * frame also costs real frame time. A weighted scrim gets most of the
     * separation for none of that.
     */
    public static void scrim(GuiGraphics g, int width, int height) {
        g.fill(0, 0, width, height, Palette.SCRIM);
    }
}
