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
 * Cost: the corners are rows of pixels ({@link RoundRect}), at most 2r + 1
 * fills for a filled shape and about 4r for an outline, each batched into one
 * draw where Minecraft would otherwise draw every fill alone
 * ({@link dev.breeze.compat.Draw#shapes}). Up to 2.24.0 they were drawn
 * pixel by pixel (188 fills for one card's outline), which is what made the
 * native menus slow (2.24.1).
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
     * The corners are quarter-circles, so the curve is actually circular
     * rather than a chamfer; {@link RoundRect} turns the shape into one
     * rectangle per run of rows.
     */
    public static void fillRounded(GuiGraphics g, int x1, int y1, int x2, int y2, int r, int color) {
        if (x2 <= x1 || y2 <= y1) return;
        if (dev.breeze.devtest.MenuPerf.legacy) {
            dev.breeze.devtest.LegacyShapes.fillRounded(g, x1, y1, x2, y2, r, color);
            return;
        }
        dev.breeze.compat.Draw.shapes(g, () -> RoundRect.fill(x1, y1, x2, y2, r, (a, b, c, d) -> {
            dev.breeze.devtest.MenuPerf.rects++;
            g.fill(a, b, c, d, color);
        }));
    }

    // ── Borders ─────────────────────────────────────────────────────────────

    /** A 1px outline that follows the same curve as {@link #fillRounded}. */
    public static void roundedBorder(GuiGraphics g, int x1, int y1, int x2, int y2, int r, int color) {
        if (x2 <= x1 || y2 <= y1) return;
        if (dev.breeze.devtest.MenuPerf.legacy) {
            dev.breeze.devtest.LegacyShapes.roundedBorder(g, x1, y1, x2, y2, r, color);
            return;
        }
        dev.breeze.compat.Draw.shapes(g, () -> RoundRect.outline(x1, y1, x2, y2, r, (a, b, c, d) -> {
            dev.breeze.devtest.MenuPerf.rects++;
            g.fill(a, b, c, d, color);
        }));
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
        dev.breeze.compat.Draw.shapes(g, () -> panelShapes(g, box, fill, border));
    }

    private static void panelShapes(GuiGraphics g, Rect box, int fill, int border) {
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
        dev.breeze.compat.Draw.shapes(g, () -> surfaceShapes(g, box, hovered, active));
    }

    private static void surfaceShapes(GuiGraphics g, Rect box, boolean hovered, boolean active) {
        fillRounded(g, box.x, box.y, box.right(), box.bottom(), RADIUS_SM,
                active ? Palette.ACCENT_DIM : Palette.surfaceFor(hovered, false));
        roundedBorder(g, box.x, box.y, box.right(), box.bottom(), RADIUS_SM,
                (hovered || active) ? Palette.BORDER_HOVER : Palette.BORDER);
    }

    /** A pill-shaped toggle. Fully round ends, so radius is half the height. */
    public static void toggle(GuiGraphics g, int x, int y, int w, int h, boolean on) {
        dev.breeze.compat.Draw.shapes(g, () -> toggleShapes(g, x, y, w, h, on));
    }

    private static void toggleShapes(GuiGraphics g, int x, int y, int w, int h, boolean on) {
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
