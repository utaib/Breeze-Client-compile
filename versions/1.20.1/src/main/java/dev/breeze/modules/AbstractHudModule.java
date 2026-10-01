package dev.breeze.modules;

import dev.breeze.BreezeClient;
import dev.breeze.Category;
import dev.breeze.Module;
import dev.breeze.hud.HudHelper;
import dev.breeze.hud.HudStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * A HUD module that draws lines of text in a corner.
 *
 * Lines are buffered during {@link #draw} and painted afterwards. That ordering
 * is what makes the styling work: the background has to be drawn under the text
 * but sized from it, and right and centre alignment need the width of the widest
 * line before the first one can be positioned. Drawing straight to the screen as
 * each line arrived meant neither was possible without a frame of lag.
 *
 * Modules that draw shapes directly rather than calling {@link #line} still work
 * unchanged; they simply produce no buffered lines, so the style block sizes
 * itself from whatever bounds they report.
 */
public abstract class AbstractHudModule extends Module {

    /** Where the element is drawn this frame, resolved from its placement. */
    protected int x;
    protected int y;
    /** The constructor's position, which Reset in the HUD editor goes back to. */
    private final int defaultX;
    private final int defaultY;
    /** Anchor and offset (HudPlacement); null means the default position. */
    private dev.breeze.hud.HudPlacement placement;

    private final HudStyle style = new HudStyle();
    private final List<String> lineText = new ArrayList<>();
    private final List<Integer> lineColor = new ArrayList<>();
    /** Reused across frames. Grows to the largest line count this module has had. */
    private int[] widths = new int[8];

    private int lastW;
    private int lastH;
    /** So a throwing module reports itself once rather than once per frame. */
    private boolean drawFailureReported;
    /** When draw last finished without throwing (System.currentTimeMillis), 0 if never. */
    private long lastDrawnAt;

    protected AbstractHudModule(String name, Category category, String description, int defaultKey, int x, int y) {
        super(name, category, description, defaultKey);
        this.x = x;
        this.y = y;
        this.defaultX = x;
        this.defaultY = y;
        addAll(style.settings());
    }

    public final HudStyle style() { return style; }

    @Override
    protected final void onHudRender(GuiGraphics g, float partialTick) {
        lineText.clear();
        lineColor.clear();
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        resolvePosition(mc);

        float s = style.scaleFactor();
        boolean scaled = Math.abs(s - 1f) > 0.001f;
        if (scaled) {
            // Scale about the module's own origin, so changing the scale grows
            // the block in place instead of sliding it toward the screen corner.
            dev.breeze.compat.Draw.pushScaled(g, x, y, s);
        }

        if (drawsShapes()) {
            // Under the content, sized from the last frame's bounds: drawn
            // afterwards it would cover the icons and bars it frames.
            try { style.drawBackground(g, x, y, lastW, lastH); } catch (Throwable ignored) {}
        }

        try {
            draw(mc, g, font);
            lastDrawnAt = System.currentTimeMillis();
        } catch (Throwable error) {
            dev.breeze.ModuleManager.noteError(this);
            // A module that throws here used to render nothing and say nothing,
            // which is indistinguishable from a module that is simply switched
            // off. That is how several modules came to be described as broken
            // with no way to find out why: the stack trace was discarded every
            // frame, sixty times a second.
            //
            // Swallowing it is still right, because letting it escape would take
            // the whole HUD down and probably the game with it. Reporting it is
            // what was missing. Once per module, because the alternative is a
            // log line per frame.
            if (!drawFailureReported) {
                drawFailureReported = true;
                BreezeClient.LOGGER.error(
                        "HUD module '{}' threw while drawing and has been left blank. "
                                + "This is reported once; fix the cause rather than the symptom.",
                        getName(), error);
            }
        }

        try {
            if (!lineText.isEmpty()) {
                // Widths are measured once and kept. font.width walks the string
                // glyph by glyph, and this runs for every line of every enabled
                // HUD module every frame; measuring again for alignment would
                // double that for nothing.
                int n = lineText.size();
                if (widths.length < n) widths = new int[n];
                int w = 0;
                for (int i = 0; i < n; i++) {
                    widths[i] = font.width(lineText.get(i));
                    if (widths[i] > w) w = widths[i];
                }
                int lh = style.lineHeight(font);
                int h = n * lh - style.lineGap.value;

                style.drawBackground(g, x, y, w, h);

                for (int i = 0; i < n; i++) {
                    g.drawString(font, lineText.get(i), style.lineX(x, w, widths[i]), y + i * lh,
                            lineColor.get(i), style.textShadow.value);
                }
                lastW = w;
                lastH = h;
            } else if (!drawsShapes()) {
                // A module that painted itself without declaring drawsShapes.
                // The only thing left is the frame around it, sized from
                // whatever it last reported.
                style.drawBackground(g, x, y, lastW, lastH);
            }
        } catch (Throwable ignored) {}

        if (scaled) dev.breeze.compat.Draw.pop(g);
    }

    protected abstract void draw(Minecraft mc, GuiGraphics g, Font font);

    /**
     * True for a module that draws icons or shapes itself and reports its size
     * with {@link #bounds}. Its background is then drawn before it rather than
     * over it.
     */
    protected boolean drawsShapes() { return false; }

    protected void line(GuiGraphics g, Font font, String s) {
        line(g, font, s, style.textColor.argb);
    }

    protected void line(GuiGraphics g, Font font, String s, int color) {
        lineText.add(style.applyCase(s == null ? "" : s));
        // The configured text colour only overrides plain white. A module that
        // passes a meaningful colour, such as red for low health, is saying
        // something the user's preference should not erase.
        lineColor.add(color == HudHelper.WHITE ? style.textColor.argb : color);
    }

    /** For shape-drawing modules: report the bounds so the style can frame them. */
    protected final void bounds(int w, int h) {
        lastW = w;
        lastH = h;
    }

    /** When the module last drew without an error, for the self-test; 0 if never. */
    public long lastDrawnAt() { return lastDrawnAt; }

    /** Whether draw has thrown (reported once in the log). */
    public boolean drawFailed() { return drawFailureReported; }

    public int getHudX() { return x; }

    public int getHudY() { return y; }

    /**
     * Places the element's top-left corner at (x, y) on the current screen;
     * it is stored anchored to the nearest edges (HudPlacement.of), so it keeps
     * its place relative to them when the screen changes size.
     */
    public void setHudPos(int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        int[] c = dev.breeze.hud.HudPlacement.clamp(x, y, getHudW(), getHudH(), sw, sh);
        this.x = c[0];
        this.y = c[1];
        this.placement = dev.breeze.hud.HudPlacement.of(c[0], c[1], getHudW(), getHudH(), sw, sh);
    }

    /** The saved placement, or null while the element sits at its default position. */
    public dev.breeze.hud.HudPlacement getPlacement() { return placement; }

    public void setPlacement(dev.breeze.hud.HudPlacement placement) {
        this.placement = placement;
        resolvePosition(Minecraft.getInstance());
    }

    /** Back to the default position. */
    public void resetPlacement() {
        setPlacement(null);
    }

    /**
     * Where the element sits until it is moved: the constructor's position,
     * top-left anchored. A module that belongs elsewhere by default (the
     * inventory, top right) overrides this.
     */
    protected dev.breeze.hud.HudPlacement defaultPlacement() {
        return dev.breeze.hud.HudPlacement.topLeft(defaultX, defaultY);
    }

    /** x and y for this frame: the placement resolved on the current screen, kept wholly on it. */
    private void resolvePosition(Minecraft mc) {
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        if (sw <= 0 || sh <= 0) return;
        dev.breeze.hud.HudPlacement p = placement != null ? placement : defaultPlacement();
        int[] at = p.resolve(getHudW(), getHudH(), sw, sh);
        x = at[0];
        y = at[1];
    }

    /** Scaled, because this is what the HUD editor draws a handle around. */
    public int getHudW() { return Math.max((int) (Math.max(lastW, 24) * style.scaleFactor()), 24); }

    public int getHudH() { return Math.max((int) (Math.max(lastH, 10) * style.scaleFactor()), 10); }
}
