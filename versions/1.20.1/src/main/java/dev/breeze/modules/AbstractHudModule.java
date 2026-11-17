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

    /**
     * Where the content is drawn this frame: inside the background panel, so
     * the panel's padding away from the box's top-left corner.
     */
    protected int x;
    protected int y;
    /** The element's box (panel included) this frame: what placement, the editor and the tests see. */
    private int boxX;
    private int boxY;
    /** The constructor's position, which Reset in the HUD editor goes back to. */
    private final int defaultX;
    private final int defaultY;
    /** Anchor and offset (HudPlacement); null means the default position. */
    private dev.breeze.hud.HudPlacement placement;

    private final HudStyle style = new HudStyle(!drawsShapes());
    private final List<String> lineText = new ArrayList<>();
    /** Per line: its colour. Reused across frames, as widths is. */
    private int[] lineColor = new int[8];
    /** Per line: the item or game picture before the text (Minecraft's own), or null. */
    private final List<LineIcon> lineIcon = new ArrayList<>();
    /** What the last frame drew before its lines, for the self-test: item ids and texture paths. */
    private final List<String> drawnIcons = new ArrayList<>();
    private static final boolean RECORD_ICONS = System.getProperty("breeze.autotest") != null;
    /** An item, drawn by Minecraft's item renderer, or a game texture by name and places to look. */
    private record LineIcon(net.minecraft.world.item.ItemStack stack, String texture,
                            List<dev.breeze.ui.ModuleIcons.Source> sources, int size) {}
    private static final int ICON = 16;
    private static final int ICON_GAP = 2;
    /** Reused across frames. Grows to the largest line count this module has had. */
    private int[] widths = new int[8];

    /**
     * How often a text element works out what it shows: 20 times a second,
     * the game's own tick rate. It is drawn every frame from what it worked
     * out last. At 300 frames a second that is one frame in fifteen building
     * strings, measuring them and reading the game, instead of every frame
     * (2.29.0; before, every element did all of it every frame, and the
     * strings alone were thousands of bytes of garbage per element per frame).
     */
    private static final long CONTENT_NANOS = 50_000_000L;
    /** When the lines were last worked out (System.nanoTime), and whether they ever were. */
    private long contentAt;
    private boolean contentBuilt;
    /** The measured block of the lines worked out last. */
    private int contentW, contentH, contentLineHeight, contentPad, contentTextDy;
    private boolean contentIcons;
    /** The lines' paint as one batch (compat/Draw.shapes); made once, not per frame. */
    private GuiGraphics paintG;
    private Font paintFont;
    private Minecraft paintMc;
    private final Runnable paintLines = this::paintLinesSafely;
    /** A shape element's draw as one batch, for those that say batchesDraws. */
    private final Runnable drawBatched = this::drawSafely;
    private Throwable batchError;
    /** The default place, made once (it was a new object per frame). */
    private dev.breeze.hud.HudPlacement defaultPlace;

    private int lastW;
    private int lastH;
    /** So a throwing module reports itself once rather than once per frame. */
    private boolean drawFailureReported;
    /** When draw last finished without throwing (System.currentTimeMillis), 0 if never. */
    private long lastDrawnAt;
    /** Whether this session has checked the default place against the other elements (once). */
    private boolean defaultPlaceChecked;

    protected AbstractHudModule(String name, Category category, String description, int defaultKey, int x, int y) {
        super(name, category, description, defaultKey);
        this.x = x;
        this.y = y;
        this.boxX = x;
        this.boxY = y;
        this.defaultX = x;
        this.defaultY = y;
        addAll(style.settings());
    }

    public final HudStyle style() { return style; }

    /**
     * False when there is nothing to show this frame (the Scoreboard with no
     * sidebar, or hidden): then not even the background is drawn.
     */
    protected boolean hasContent() { return true; }

    private long dueAt;
    private boolean dueOnce;

    /**
     * For a shape element: true when what it shows is due to be worked out
     * again, at most 20 times a second. It still paints every frame; this is
     * for the reading, measuring and laying out behind the paint.
     */
    protected final boolean due() {
        long now = System.nanoTime();
        if (dueOnce && now - dueAt < CONTENT_NANOS) return false;
        dueOnce = true;
        dueAt = now;
        return true;
    }

    /**
     * True for an element whose text must be worked out every frame. None of
     * the built-in ones needs it: the smallest step any of them shows is a
     * game tick or longer.
     */
    protected boolean everyFrame() { return false; }

    @Override
    protected final void onHudRender(GuiGraphics g, float partialTick) {
        if (!hasContent()) return;
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        resolvePosition(mc);

        float s = style.scaleFactor();
        boolean scaled = Math.abs(s - 1f) > 0.001f;
        if (scaled) {
            // Scale about the element's own corner, so changing the scale grows
            // the block in place instead of sliding it toward the screen corner.
            dev.breeze.compat.Draw.pushScaled(g, boxX, boxY, s);
        }

        boolean shapes = drawsShapes();
        if (shapes && backgroundNow()) {
            // Under the content, sized from the last frame's bounds: drawn
            // afterwards it would cover the icons and bars it frames.
            try { style.drawBackground(g, x, y, lastW, lastH); } catch (Throwable ignored) {}
        }

        // A shape element paints itself in draw, so it runs every frame. A
        // text element only records lines there, so it runs when its content
        // is due, and the frames in between paint what it recorded.
        long now = System.nanoTime();
        boolean build = shapes || !contentBuilt || everyFrame() || now - contentAt >= CONTENT_NANOS;
        if (build) {
            boolean cost = dev.breeze.perf.FrameCost.enabled();
            long t0 = cost ? System.nanoTime() : 0, b0 = cost ? dev.breeze.perf.FrameCost.allocated() : 0;
            if (!contentBuilt) {
                // Elements switched on together would otherwise all work out
                // their content on the same frames; each starts at its own
                // point of the 50 ms instead.
                contentAt = now - Math.floorMod(getName().hashCode(), 50) * 1_000_000L;
                contentBuilt = true;
            } else {
                contentAt = now;
            }
            lineText.clear();
            lineIcon.clear();
            try {
                if (shapes && batchesDraws()) {
                    // Fills and text only: one batch where the game would
                    // otherwise make a draw of each (compat/Draw.shapes).
                    paintMc = mc;
                    paintG = g;
                    paintFont = font;
                    batchError = null;
                    dev.breeze.compat.Draw.shapes(g, drawBatched);
                    paintG = null;
                    Throwable failed = batchError;
                    batchError = null;
                    if (failed != null) throw failed;
                } else {
                    draw(mc, g, font);
                }
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
                measureLines(font);
            } catch (Throwable ignored) {}
            if (cost) dev.breeze.perf.FrameCost.module(dev.breeze.perf.FrameCost.HUD_CONTENT, getName(), t0, b0);
        } else if (!drawFailureReported) {
            lastDrawnAt = System.currentTimeMillis();
        }

        try {
            if (!lineText.isEmpty()) {
                paintG = g;
                paintFont = font;
                // Text alone goes out as one batch with its panel where the
                // game would otherwise draw the panel and every line apart
                // (compat/Draw.shapes). A picture is drawn at once by the
                // game, so lines with pictures keep the plain order.
                if (contentIcons) paintLines();
                else dev.breeze.compat.Draw.shapes(g, paintLines);
                paintG = null;
                lastW = contentW;
                lastH = contentH;
            } else if (!shapes) {
                // A module that painted itself without declaring drawsShapes.
                // The only thing left is the frame around it, sized from
                // whatever it last reported.
                style.drawBackground(g, x, y, lastW, lastH);
            }
        } catch (Throwable ignored) {}

        if (scaled) dev.breeze.compat.Draw.pop(g);

        if (!defaultPlaceChecked && placement == null && lastDrawnAt > 0) {
            defaultPlaceChecked = true;
            avoidOthers(mc);
        }
    }

    /**
     * Measures the recorded lines once. font.width walks the string glyph by
     * glyph, so it runs when the lines change, not when they are painted.
     * Lines with a picture are 16 high, the item's size, and every line's
     * text starts after the picture column, so text lines without one still
     * line up.
     */
    private void measureLines(Font font) {
        int n = lineText.size();
        if (widths.length < n) widths = new int[n];
        boolean icons = false;
        for (int i = 0; i < n; i++) icons |= lineIcon.get(i) != null;
        int pad = icons ? ICON + ICON_GAP : 0;
        int w = 0;
        for (int i = 0; i < n; i++) {
            widths[i] = pad + font.width(lineText.get(i));
            if (widths[i] > w) w = widths[i];
        }
        int lh = icons ? Math.max(style.lineHeight(font), ICON + style.lineGap.value) : style.lineHeight(font);
        contentIcons = icons;
        contentPad = pad;
        contentW = w;
        contentLineHeight = lh;
        contentH = n * lh - style.lineGap.value;
        contentTextDy = icons ? (ICON - font.lineHeight) / 2 + 1 : 0;
    }

    /**
     * The batch's body must not throw: Minecraft's drawManaged has no
     * "finally", so an exception inside it would leave every later draw of
     * the frame unflushed.
     */
    private void paintLinesSafely() {
        try {
            paintLines();
        } catch (Throwable ignored) {
        }
    }

    private void drawSafely() {
        try {
            draw(paintMc, paintG, paintFont);
        } catch (Throwable error) {
            batchError = error;
        }
    }

    /** Paints the panel and the recorded lines at this frame's place. */
    private void paintLines() {
        GuiGraphics g = paintG;
        Font font = paintFont;
        int n = lineText.size();
        style.drawBackground(g, x, y, contentW, contentH);
        if (RECORD_ICONS) drawnIcons.clear();
        boolean shadow = style.textShadow.value;
        for (int i = 0; i < n; i++) {
            int lx = style.lineX(x, contentW, widths[i]);
            int ly = y + i * contentLineHeight;
            LineIcon li = lineIcon.get(i);
            if (li != null) drawIcon(g, li, lx, ly);
            g.drawString(font, lineText.get(i), lx + contentPad, ly + contentTextDy, lineColor[i], shadow);
        }
    }

    /**
     * The first time an element that has never been moved is drawn, if its
     * default place is already taken by another element on screen, it moves
     * to the nearest free place (HudPlacement.freeSpot, the way Arrange
     * stacks) and that place is saved. Many elements share a default corner,
     * so switching on several used to pile them on top of each other. An
     * element the player has placed is never moved. Minecraft's own hotbar
     * and bars count as taken, so an element is never moved onto them.
     */
    private void avoidOthers(Minecraft mc) {
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        if (sw <= 0 || sh <= 0) return;
        List<dev.breeze.hud.HudPlacement.Box> taken = new ArrayList<>();
        taken.add(vanillaBars(sw, sh));
        long now = System.currentTimeMillis();
        for (Module m : dev.breeze.ModuleManager.getModules()) {
            if (m == this || !(m instanceof AbstractHudModule o) || !o.isEnabled()) continue;
            if (o.lastDrawnAt == 0 || now - o.lastDrawnAt > 1_000) continue;
            taken.add(new dev.breeze.hud.HudPlacement.Box(o.boxX, o.boxY, o.getHudW(), o.getHudH()));
        }
        int[] at = dev.breeze.hud.HudPlacement.freeSpot(boxX, boxY, getHudW(), getHudH(), taken, sw, sh, 4, 2);
        if (at == null || (at[0] == boxX && at[1] == boxY)) return;
        setHudPos(at[0], at[1]);
        dev.breeze.ui.HudLayout.set(getName(), placement);
        dev.breeze.ui.HudLayout.save();
    }

    /**
     * Minecraft's hotbar (182 wide, centred, 22 high) with the health, food,
     * armour and experience rows above it, in GUI-scaled pixels.
     */
    public static dev.breeze.hud.HudPlacement.Box vanillaBars(int sw, int sh) {
        return new dev.breeze.hud.HudPlacement.Box(sw / 2 - 91, sh - 50, 182, 50);
    }

    protected abstract void draw(Minecraft mc, GuiGraphics g, Font font);

    /**
     * True for a module that draws icons or shapes itself and reports its size
     * with {@link #bounds}. Its background is then drawn before it rather than
     * over it.
     */
    protected boolean drawsShapes() { return false; }

    /**
     * True for a shape element that draws only fills and text (no textures,
     * no items, no clipping): its draw then goes out as one batch. Textures
     * are drawn at once by the game on 1.20 to 1.21.1, so one inside a batch
     * would end up under the fills made before it.
     */
    protected boolean batchesDraws() { return false; }

    /**
     * False in a frame where a shape-drawing module keeps its box but shows
     * nothing in it (Lyrics between songs), so no empty panel is drawn.
     */
    protected boolean backgroundNow() { return true; }

    protected void line(GuiGraphics g, Font font, String s) {
        line(g, font, s, style.textColor.argb);
    }

    protected void line(GuiGraphics g, Font font, String s, int color) {
        addLine(s, color, null);
    }

    /** A line with the item drawn before it by Minecraft's own item renderer (count and durability bar included). */
    protected void itemLine(GuiGraphics g, Font font, net.minecraft.world.item.ItemStack stack, String s, int color) {
        addLine(s, color, stack == null || stack.isEmpty() ? null : new LineIcon(stack, null, null, ICON));
    }

    /**
     * A line with one of Minecraft's own pictures before it
     * ({@link dev.breeze.ui.GameTextures}), size pixels square and centred in
     * the 16 pixel column (9 for the HUD's own 9 pixel icons, so they are not
     * stretched); text alone if this game has none of its places.
     */
    protected void textureLine(GuiGraphics g, Font font, String key, List<dev.breeze.ui.ModuleIcons.Source> sources,
                               int size, String s, int color) {
        boolean found = !sources.isEmpty() && dev.breeze.ui.ModuleIconCache.texture(key, sources) != null;
        addLine(s, color, found ? new LineIcon(null, key, sources, Math.max(1, Math.min(ICON, size))) : null);
    }

    private void drawIcon(GuiGraphics g, LineIcon li, int x, int y) {
        if (li.stack() != null) {
            g.renderItem(li.stack(), x, y);
            g.renderItemDecorations(Minecraft.getInstance().font, li.stack(), x, y);
            if (RECORD_ICONS) drawnIcons.add(String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(li.stack().getItem())));
        } else {
            int off = (ICON - li.size()) / 2;
            dev.breeze.ui.ModuleIconCache.drawTexture(g, li.texture(), li.sources(), x + off, y + off, li.size());
            if (RECORD_ICONS) {
                dev.breeze.ui.ModuleIconCache.Icon icon = dev.breeze.ui.ModuleIconCache.texture(li.texture(), li.sources());
                drawnIcons.add(icon == null ? li.texture() + " (none)" : icon.source());
            }
        }
    }

    /** For the self-test: the pictures the last frame drew before its lines (item ids, texture paths). */
    public List<String> drawnIcons() {
        return List.copyOf(drawnIcons);
    }

    private void addLine(String s, int color, LineIcon icon) {
        int i = lineText.size();
        lineIcon.add(icon);
        lineText.add(style.applyCase(s == null ? "" : s));
        if (lineColor.length <= i) lineColor = java.util.Arrays.copyOf(lineColor, Math.max(8, i * 2));
        // The configured text colour only overrides plain white. A module that
        // passes a meaningful colour, such as red for low health, is saying
        // something the user's preference should not erase.
        lineColor[i] = color == HudHelper.WHITE ? style.textColor.argb : color;
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

    /** The box's left edge, panel included. */
    public int getHudX() { return boxX; }

    public int getHudY() { return boxY; }

    /**
     * Places the element's top-left corner at (x, y) on the current screen;
     * it is stored anchored to the nearest edges (HudPlacement.of), so it keeps
     * its place relative to them when the screen changes size.
     */
    public void setHudPos(int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) return;
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        int[] c = dev.breeze.hud.HudPlacement.clamp(x, y, getHudW(), getHudH(), sw, sh);
        placeBox(c[0], c[1]);
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
     * inventory, bottom right) overrides this.
     */
    protected dev.breeze.hud.HudPlacement defaultPlacement() {
        if (defaultPlace == null) defaultPlace = dev.breeze.hud.HudPlacement.topLeft(defaultX, defaultY);
        return defaultPlace;
    }

    /** x and y for this frame: the placement resolved on the current screen, kept wholly on it. */
    private void resolvePosition(Minecraft mc) {
        // Saved placements are applied in the client entrypoint, which Fabric
        // runs inside Minecraft's constructor before the window is created, on
        // every version. Reading the window there crashed every player who had
        // a saved layout (2.8.0 to 2.9.3). The position resolves on the first frame.
        if (mc == null || mc.getWindow() == null) return;
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        if (sw <= 0 || sh <= 0) return;
        dev.breeze.hud.HudPlacement p = placement != null ? placement : defaultPlacement();
        placedW = getHudW();
        placedH = getHudH();
        placeBox(p.resolveX(placedW, sw), p.resolveY(placedH, sh));
    }

    /**
     * The size this frame was placed with: the previous frame's bounds, since
     * a text element's width is known only once its lines are measured. When
     * the text changes width (FPS going from 9 to 10, an item appearing for
     * the despawn timer) the next frame places it with the new size.
     */
    private int placedW, placedH;

    public int placedW() { return placedW; }

    public int placedH() { return placedH; }

    /** The box at (bx, by); the content starts the panel's padding inside it, before scaling. */
    private void placeBox(int bx, int by) {
        boxX = bx;
        boxY = by;
        int pad = style.boxPad();
        x = bx + pad;
        y = by + pad;
    }

    /**
     * The box's size: content plus the panel's padding on both sides, scaled,
     * because this is what the HUD editor draws a handle around and what other
     * elements are kept clear of.
     */
    public int getHudW() {
        return Math.max((int) ((Math.max(lastW, 24) + 2 * style.boxPad()) * style.scaleFactor()), 24);
    }

    public int getHudH() {
        return Math.max((int) ((Math.max(lastH, 10) + 2 * style.boxPad()) * style.scaleFactor()), 10);
    }
}
