package dev.breeze.hud;

import dev.breeze.settings.Setting;
import dev.breeze.ui.Glass;
import dev.breeze.ui.Palette;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * The appearance settings every HUD module gets.
 *
 * Built as one shared block rather than per module on purpose. There are 35 HUD
 * modules; giving each its own colour, background, radius, scale and alignment
 * settings by hand would be 35 chances to spell a config key differently, and
 * every new module would start with none of them. One block means adding a
 * module gets the whole set for free, and a change here reaches all of them.
 *
 * Modules that need something specific still add their own settings on top;
 * these are the ones that apply to anything drawn as text in a corner.
 */
public final class HudStyle {

    private static final String LOOK = "Appearance";
    private static final String TEXT = "Text";
    private static final String LAYOUT = "Layout";

    /**
     * Saved as "panel": up to 2.9.2 it was "bg" and defaulted to None, so text
     * elements were bare words on the world. Every setting is written to the
     * config, so keeping the old key would have kept None for everyone who had
     * played once; the new key gives everyone the panel once.
     */
    public final Setting.Mode background;
    public final Setting.Color bgColor = new Setting.Color("bgColor", "Background colour", LOOK, 0xA0101420);
    public final Setting.Color bgColor2 = new Setting.Color("bgColor2", "Gradient to", LOOK, 0x9078B2FF);
    public final Setting.Int radius = new Setting.Int("radius", "Corner radius", LOOK, 4, 0, 10, "px");
    public final Setting.Bool border = new Setting.Bool("border", "Border", LOOK, false);
    public final Setting.Color borderColor = new Setting.Color("borderColor", "Border colour", LOOK, 0xFF2B3140);
    public final Setting.Bool shadow = new Setting.Bool("shadow", "Drop shadow", LOOK, false);

    public final Setting.Color textColor = new Setting.Color("textColor", "Text colour", TEXT, 0xFFFFFFFF);
    public final Setting.Bool textShadow = new Setting.Bool("textShadow", "Text shadow", TEXT, true);
    public final Setting.Mode textCase =
            new Setting.Mode("textCase", "Text case", TEXT, new String[]{"Normal", "Upper", "Lower"}, 0);

    public final Setting.Mode align =
            new Setting.Mode("align", "Alignment", LAYOUT, new String[]{"Left", "Center", "Right"}, 0);
    public final Setting.Int scale = new Setting.Int("scale", "Scale", LAYOUT, 100, 50, 200, "%");
    public final Setting.Int padding = new Setting.Int("padding", "Padding", LAYOUT, 3, 0, 12, "px");
    public final Setting.Int lineGap = new Setting.Int("lineGap", "Line spacing", LAYOUT, 1, 0, 8, "px");

    private final List<Setting> all = new ArrayList<>();

    /**
     * @param panel whether the element sits on a background panel by default.
     *              Elements that draw their own boxes (keystrokes, armour,
     *              inventory) start without one.
     */
    public HudStyle(boolean panel) {
        background = new Setting.Mode("panel", "Background", LOOK,
                new String[]{"None", "Solid", "Gradient", "Outline"}, panel ? 1 : 0);
        all.add(background);
        all.add(bgColor);
        all.add(bgColor2);
        all.add(radius);
        all.add(border);
        all.add(borderColor);
        all.add(shadow);
        all.add(textColor);
        all.add(textShadow);
        all.add(textCase);
        all.add(align);
        all.add(scale);
        all.add(padding);
        all.add(lineGap);
    }

    public List<Setting> settings() {
        return all;
    }

    // ── Derived values used by the renderer ─────────────────────────────────

    public boolean hasBackground() {
        return !background.is("None");
    }

    /**
     * How far the panel reaches past the content on each side. It counts as
     * part of the element, so placing, dragging and keeping elements apart all
     * work on the box the player sees.
     */
    public int boxPad() {
        return hasBackground() ? padding.value : 0;
    }

    public float scaleFactor() {
        return scale.value / 100f;
    }

    /** Line height including the configured gap. */
    public int lineHeight(Font font) {
        return font.lineHeight + lineGap.value;
    }

    public String applyCase(String s) {
        if (s == null) return "";
        if (textCase.is("Upper")) return s.toUpperCase();
        if (textCase.is("Lower")) return s.toLowerCase();
        return s;
    }

    /**
     * Where a line of the given width starts, given the block width.
     *
     * Right and centre alignment need the block width, which is why HUD modules
     * measure before they draw rather than drawing as they go.
     */
    public int lineX(int blockX, int blockW, int lineW) {
        if (align.is("Center")) return blockX + (blockW - lineW) / 2;
        if (align.is("Right")) return blockX + blockW - lineW;
        return blockX;
    }

    /**
     * Draw the panel behind a HUD block.
     *
     * Uses {@link Glass} for real rounded corners, the same shapes the menus
     * use, so a HUD panel and a menu panel are recognisably the same design
     * rather than two different rectangles.
     */
    public void drawBackground(GuiGraphics g, int x, int y, int w, int h) {
        if (!hasBackground() || w <= 0 || h <= 0) return;
        int pad = padding.value;
        int x1 = x - pad, y1 = y - pad, x2 = x + w + pad, y2 = y + h + pad;
        int r = radius.value;

        if (shadow.value) {
            for (int i = 3; i >= 1; i--) {
                int a = 0x14 - i * 4;
                if (a <= 0) continue;
                Glass.fillRounded(g, x1 - i, y1 - i + 1, x2 + i, y2 + i + 1, r + i,
                        Palette.alpha(Palette.SHADOW, a));
            }
        }

        if (background.is("Gradient")) {
            // fillGradient has no rounded form, so the rounded fill provides the
            // silhouette and the gradient is drawn inside it. Insetting by the
            // radius keeps the gradient off the curve, where a square edge would
            // otherwise show through the corner.
            Glass.fillRounded(g, x1, y1, x2, y2, r, bgColor.argb);
            if (y2 - y1 > r * 2) {
                g.fillGradient(x1 + r, y1 + 1, x2 - r, y2 - 1, bgColor.argb, bgColor2.argb);
            }
        } else if (background.is("Solid")) {
            Glass.fillRounded(g, x1, y1, x2, y2, r, bgColor.argb);
        }

        if (border.value || background.is("Outline")) {
            Glass.roundedBorder(g, x1, y1, x2, y2, r, borderColor.argb);
        }
    }
}
