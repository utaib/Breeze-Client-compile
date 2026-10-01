package dev.breeze.ui;

/**
 * An immutable rectangle with slicing helpers.
 *
 * Every Breeze screen previously computed its own panel geometry with bespoke
 * arithmetic, which is how the UI ended up with tabs colliding with icons, cape
 * cards drawn outside their panel, and sliders overlapping the buttons beneath
 * them. Those are all the same bug: two pieces of code deciding independently
 * where something goes.
 *
 * The model here is "cut": you start with the whole area and slice pieces off,
 * so a region can never be handed out twice and leftover space is always
 * explicit. A header cut from the top genuinely removes that space from what
 * the body can use.
 *
 * <pre>
 *   Rect panel  = Rect.centered(width, height, 420, 260).inset(Spacing.PANEL);
 *   Rect header = panel.cutTop(Spacing.HEADER_H);
 *   Rect footer = panel.cutBottom(Spacing.FOOTER_H);   // panel is now the body
 * </pre>
 *
 * Note that {@code cutTop} and friends return the removed strip and are the
 * mutating-style helpers on a builder; this class is immutable, so they return
 * the strip and you keep the remainder from {@link #minusTop(int)}. For the
 * common case use {@link Cursor}.
 */
public final class Rect {
    public final int x;
    public final int y;
    public final int w;
    public final int h;

    public Rect(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = Math.max(0, w);
        this.h = Math.max(0, h);
    }

    /** A rect of at most maxW x maxH, centred in a screen of screenW x screenH. */
    public static Rect centered(int screenW, int screenH, int maxW, int maxH) {
        int w = Math.min(screenW - Spacing.SCREEN_MARGIN * 2, maxW);
        int h = Math.min(screenH - Spacing.SCREEN_MARGIN * 2, maxH);
        return new Rect((screenW - w) / 2, (screenH - h) / 2, w, h);
    }

    public int right() { return x + w; }
    public int bottom() { return y + h; }
    public int centerX() { return x + w / 2; }
    public int centerY() { return y + h / 2; }

    /** Shrink equally on all sides. */
    public Rect inset(int all) { return inset(all, all); }

    public Rect inset(int horizontal, int vertical) {
        return new Rect(x + horizontal, y + vertical, w - horizontal * 2, h - vertical * 2);
    }

    /** The top strip of height n. Never taller than this rect. */
    public Rect top(int n) { return new Rect(x, y, w, Math.min(n, h)); }

    /** The bottom strip of height n. */
    public Rect bottom(int n) {
        int hh = Math.min(n, h);
        return new Rect(x, y + h - hh, w, hh);
    }

    /** The left column of width n. */
    public Rect left(int n) { return new Rect(x, y, Math.min(n, w), h); }

    /** The right column of width n. */
    public Rect right(int n) {
        int ww = Math.min(n, w);
        return new Rect(x + w - ww, y, ww, h);
    }

    /** What remains after removing a top strip of height n. */
    public Rect minusTop(int n) {
        int cut = Math.min(n, h);
        return new Rect(x, y + cut, w, h - cut);
    }

    public Rect minusBottom(int n) { return new Rect(x, y, w, h - Math.min(n, h)); }

    public Rect minusLeft(int n) {
        int cut = Math.min(n, w);
        return new Rect(x + cut, y, w - cut, h);
    }

    public Rect minusRight(int n) { return new Rect(x, y, w - Math.min(n, w), h); }

    /** Move without resizing. */
    public Rect offset(int dx, int dy) { return new Rect(x + dx, y + dy, w, h); }

    /** A rect of the given size, centred inside this one. */
    public Rect centeredInside(int cw, int ch) {
        return new Rect(x + (w - cw) / 2, y + (h - ch) / 2, cw, ch);
    }

    public boolean contains(double mx, double my) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** True when there is no room left to draw anything meaningful. */
    public boolean isEmpty() { return w <= 0 || h <= 0; }

    @Override
    public String toString() {
        return "Rect[" + x + "," + y + " " + w + "x" + h + "]";
    }
}
