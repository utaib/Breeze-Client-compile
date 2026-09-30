package dev.breeze.ui;

import dev.breeze.compat.Ids;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.resources.ResourceLocation;

public final class UiRender {

    public static final ResourceLocation LOGO = Ids.breeze("textures/gui/breeze_logo.png");

    private UiRender() {}

    /**
     * Draw text that is guaranteed not to escape {@code maxW}, adding an
     * ellipsis when it had to be cut.
     *
     * Every screen previously mixed clipped and unclipped draws, sometimes on
     * adjacent lines of the same card, which is why long names and server
     * addresses spilled across panel edges. Route every variable-length string
     * through this and the rule cannot be applied inconsistently.
     *
     * @return the width actually drawn, so callers can lay out what follows.
     */
    public static int textClipped(GuiGraphics g, Font font, String s, int x, int y, int maxW, int color) {
        if (s == null || s.isEmpty() || maxW <= 0) return 0;
        if (font.width(s) <= maxW) {
            g.drawString(font, s, x, y, color, false);
            return font.width(s);
        }
        int ellipsisW = font.width("...");
        // No room for even the ellipsis: draw what fits and accept a hard cut.
        if (maxW <= ellipsisW) {
            String hard = font.plainSubstrByWidth(s, maxW);
            g.drawString(font, hard, x, y, color, false);
            return font.width(hard);
        }
        String cut = font.plainSubstrByWidth(s, maxW - ellipsisW) + "...";
        g.drawString(font, cut, x, y, color, false);
        return font.width(cut);
    }

    /** Right-aligned variant, for values in a settings row. */
    public static void textRight(GuiGraphics g, Font font, String s, int rightEdge, int y, int color) {
        if (s == null || s.isEmpty()) return;
        g.drawString(font, s, rightEdge - font.width(s), y, color, false);
    }

    /**
     * A raised surface: soft drop shadow, fill, hairline border.
     *
     * This is the single visual difference between "a Minecraft overlay" and
     * "a desktop application". A flat filled rectangle has no relationship to
     * what is behind it; a shadow plus a lighter fill plus a 1px border reads
     * as a physical layer above the page, which is what the launcher does and
     * what the mod was not doing anywhere.
     *
     * The shadow is four stacked translucent bands rather than a real blur,
     * because Minecraft's GUI layer has no cheap blur and four extra fills is
     * far less costly per frame than a shader pass.
     */
    public static void panel(GuiGraphics g, Rect r, int fill, int border) {
        if (r.isEmpty()) return;
        for (int i = 4; i >= 1; i--) {
            int a = 0x14 - i * 3;
            if (a <= 0) continue;
            g.fill(r.x - i, r.y - i + 2, r.right() + i, r.bottom() + i + 2, Palette.alpha(Palette.SHADOW, a));
        }
        rounded(g, r.x, r.y, r.w, r.h, fill);
        outline(g, r, border);
    }

    /** Default panel styling straight from the launcher palette. */
    public static void panel(GuiGraphics g, Rect r) {
        panel(g, r, Palette.SURFACE, Palette.BORDER);
    }

    /** A 1px hairline around a rect, corners omitted to fake a radius. */
    public static void outline(GuiGraphics g, Rect r, int color) {
        if (r.isEmpty()) return;
        g.fill(r.x + 1, r.y, r.right() - 1, r.y + 1, color);
        g.fill(r.x + 1, r.bottom() - 1, r.right() - 1, r.bottom(), color);
        g.fill(r.x, r.y + 1, r.x + 1, r.bottom() - 1, color);
        g.fill(r.right() - 1, r.y + 1, r.right(), r.bottom() - 1, color);
    }

    /**
     * A horizontal separator at one third opacity.
     *
     * Full-strength dividers fight the content; the launcher uses a very faint
     * line and lets spacing do most of the separating.
     */
    public static void divider(GuiGraphics g, int x, int y, int w) {
        g.fill(x, y, x + w, y + 1, Palette.BORDER);
    }

    /** Centre a single line inside a rect. */
    public static void textCentered(GuiGraphics g, Font font, String s, Rect r, int color) {
        if (s == null || s.isEmpty() || r.isEmpty()) return;
        String fit = font.width(s) <= r.w ? s : font.plainSubstrByWidth(s, r.w);
        g.drawString(font, fit, r.x + (r.w - font.width(fit)) / 2,
                r.y + (r.h - font.lineHeight) / 2 + 1, color, false);
    }

    public static void rounded(GuiGraphics g, int x, int y, int w, int h, int color) {
        if (w <= 0 || h <= 0) return;
        g.fill(x + 1, y, x + w - 1, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    public static void border(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x + 1, y, x + w - 1, y + 1, color);
        g.fill(x + 1, y + h - 1, x + w - 1, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    public static void hGradient(GuiGraphics g, int x, int y, int w, int h, int from, int to) {
        if (w <= 0) return;
        int steps = Math.min(w, 24);
        int done = 0;
        for (int i = 0; i < steps; i++) {
            int next = x + (int) ((long) w * (i + 1) / steps);
            int c = Theme.lerp(from, to, steps <= 1 ? 0f : (float) i / (steps - 1));
            g.fill(x + done, y, next, y + h, c);
            done = next - x;
        }
    }

    public static void accentBar(GuiGraphics g, int x, int y, int w, int h) {
        hGradient(g, x, y, w, h, Theme.primary(), Theme.secondary());
    }

    public static void toggle(GuiGraphics g, int x, int y, int w, int h, boolean on) {
        if (on) {
            hGradient(g, x + 1, y + 1, w - 2, h - 2, Theme.primary(), Theme.secondary());
        } else {
            rounded(g, x, y, w, h, Palette.SURFACE_ACTIVE);
        }
        int knob = h - 4;
        int kx = on ? x + w - knob - 2 : x + 2;
        rounded(g, kx, y + 2, knob, knob, Palette.TEXT_PRIMARY);
    }

    public static void heart(GuiGraphics g, int x, int y, boolean filled) {
        int c = filled ? Theme.primary() : Palette.TEXT_FAINT;
        g.fill(x + 1, y + 1, x + 3, y + 2, c);
        g.fill(x + 4, y + 1, x + 6, y + 2, c);
        g.fill(x, y + 2, x + 7, y + 4, c);
        g.fill(x + 1, y + 4, x + 6, y + 5, c);
        g.fill(x + 2, y + 5, x + 5, y + 6, c);
        g.fill(x + 3, y + 6, x + 4, y + 7, c);
    }

    public static void gear(GuiGraphics g, int x, int y, int size, int color) {
        int cx = x + size / 2;
        int cy = y + size / 2;
        int t = Math.max(2, size / 6);
        int r = size / 2 - 2;
        g.fill(cx - t, y, cx + t, y + size, color);
        g.fill(x, cy - t, x + size, cy + t, color);
        int d = Math.max(2, size / 5);
        g.fill(x + 1, y + 1, x + 1 + d, y + 1 + d, color);
        g.fill(x + size - 1 - d, y + 1, x + size - 1, y + 1 + d, color);
        g.fill(x + 1, y + size - 1 - d, x + 1 + d, y + size - 1, color);
        g.fill(x + size - 1 - d, y + size - 1 - d, x + size - 1, y + size - 1, color);
        rounded(g, cx - r, cy - r, r * 2, r * 2, color);
        int h = Math.max(2, size / 5);
        rounded(g, cx - h, cy - h, h * 2, h * 2, Palette.SURFACE);
    }

    public static void moveIcon(GuiGraphics g, int x, int y, int size, int color) {
        int cx = x + size / 2;
        int cy = y + size / 2;
        g.fill(cx - 1, y, cx + 1, y + size, color);
        g.fill(x, cy - 1, x + size, cy + 1, color);
        g.fill(cx - 3, y + 2, cx + 3, y + 3, color);
        g.fill(cx - 3, y + size - 3, cx + 3, y + size - 2, color);
        g.fill(x + 2, cy - 3, x + 3, cy + 3, color);
        g.fill(x + size - 3, cy - 3, x + size - 2, cy + 3, color);
    }

    /**
     * A coat hanger, for the Wardrobe entry in the sidebar.
     *
     * Drawn rather than blitted, like every other icon here, so it takes the
     * caller's colour for hover states and stays sharp at all four GUI scales
     * instead of being resampled from a fixed-size texture.
     */
    public static void hanger(GuiGraphics g, int x, int y, int size, int color) {
        int cx = x + size / 2;
        int hookTop = y + Math.max(1, size / 8);
        int shoulder = y + size / 2;
        int bar = y + size - Math.max(2, size / 5);

        // The hook curls to one side. Without it the shape reads as a plain
        // triangle at 20px, which is the size the sidebar draws it at.
        g.fill(cx, hookTop + 2, cx + 1, shoulder, color);
        g.fill(cx - 2, hookTop, cx + 1, hookTop + 1, color);
        g.fill(cx - 3, hookTop + 1, cx - 2, hookTop + 3, color);

        line(g, cx, shoulder, x + 1, bar, color);
        line(g, cx, shoulder, x + size - 1, bar, color);
        g.fill(x + 1, bar, x + size - 1, bar + 1, color);
    }

    /**
     * The Wind Charge, as a 9x9 sprite. '1' is the body, '2' the swirl, '.' is
     * transparent.
     *
     * Written out rather than blitted from a PNG for two reasons. It is tinted
     * per role, and tinting a texture means a shader colour push per player per
     * frame in the tab list. And the Wind Charge item does not exist before
     * Minecraft 1.20.5, so there is no vanilla texture to reference on the
     * versions this client mostly runs on.
     */
    private static final String[] WIND_CHARGE = {
            "..11111..",
            ".1122211.",
            "112211211",
            "122111121",
            "121122121",
            "121111221",
            "112112211",
            ".1122211.",
            "..11111..",
    };

    /**
     * Draw the Wind Charge tinted to a role's colour.
     *
     * Runs of identical pixels collapse into a single fill, so a whole row costs
     * one draw rather than nine. This runs once per player per frame in the tab
     * list, which is the reason it is worth doing.
     */
    public static void windCharge(GuiGraphics g, int x, int y, int size, int color) {
        int s = Math.max(1, size / WIND_CHARGE.length);
        int swirl = Palette.lerp(color, 0xFFFFFFFF, 0.55f);
        for (int row = 0; row < WIND_CHARGE.length; row++) {
            String line = WIND_CHARGE[row];
            int col = 0;
            while (col < line.length()) {
                char c = line.charAt(col);
                if (c == '.') { col++; continue; }
                int run = col;
                while (run < line.length() && line.charAt(run) == c) run++;
                g.fill(x + col * s, y + row * s, x + run * s, y + (row + 1) * s,
                        c == '2' ? swirl : color);
                col = run;
            }
        }
    }

    /** Width the Wind Charge occupies at a given size, for layout. */
    public static int windChargeWidth(int size) {
        return Math.max(1, size / WIND_CHARGE.length) * WIND_CHARGE.length;
    }

    /** A one pixel line between two points, Bresenham. */
    private static void line(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        int dx = Math.abs(x2 - x1), dy = Math.abs(y2 - y1);
        int sx = x1 < x2 ? 1 : -1, sy = y1 < y2 ? 1 : -1;
        int err = dx - dy;
        while (true) {
            g.fill(x1, y1, x1 + 1, y1 + 1, color);
            if (x1 == x2 && y1 == y2) break;
            int e2 = err * 2;
            if (e2 > -dy) { err -= dy; x1 += sx; }
            if (e2 < dx) { err += dx; y1 += sy; }
        }
    }

    public static void close(GuiGraphics g, int x, int y, int size, int color) {
        for (int i = 0; i < size; i++) {
            g.fill(x + i, y + i, x + i + 1, y + i + 1, color);
            g.fill(x + size - 1 - i, y + i, x + size - i, y + i + 1, color);
        }
    }

    /**
     * Source dimensions of the logo texture.
     *
     * These MUST match the actual PNG. They are a named constant because they
     * were previously written as four literal 1280s inside the blit call, so
     * resizing the asset silently sampled outside the texture and produced a
     * blank or stretched logo with nothing logged.
     *
     * The texture is 256x256. It is never drawn larger than 32 logical pixels,
     * which at Minecraft's maximum GUI scale of 4 rasterises to 128 physical
     * pixels, so 256 leaves 2x headroom. The previous 1280x1280 source cost
     * 6.25 MiB of VRAM to draw a 32px icon; this costs 0.25 MiB, which matters
     * on integrated graphics where VRAM is taken from system memory.
     */
    private static final int LOGO_PX = 256;

    public static void logo(GuiGraphics g, int x, int y, int size, float alpha) {
        RenderSystem.enableBlend();
        g.setColor(1f, 1f, 1f, alpha);
        g.blit(LOGO, x, y, size, size, 0f, 0f, LOGO_PX, LOGO_PX, LOGO_PX, LOGO_PX);
        g.setColor(1f, 1f, 1f, 1f);
        // Blend was enabled above and left on. Leaving global GL state modified
        // after a draw leaks into everything rendered afterwards, which is a
        // classic source of both visual artifacts and wasted state changes.
        RenderSystem.disableBlend();
    }
}
