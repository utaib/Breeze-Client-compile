package dev.breeze.ui;

/**
 * Rounded rectangles as a few rectangles, one per row run of their corners.
 *
 * Up to 2.24.0 the native menus drew a rounded fill with one fill call per
 * scanline of every corner and a rounded outline with one fill call per
 * point of a two-degree arc: about 23 calls for a small filled card, 188 for
 * its outline, 240 for one toggle. With the module grid open that was about
 * ten thousand fills a frame. Minecraft 1.20 to 1.21.1 draws each fill as its
 * own draw call, and from 1.21.6 (26.x included) each one is an object the
 * GUI renderer sorts and batches every frame, so the menu was slow while the
 * game around it was not (tester report, 2026-11-08, 26.1 and others).
 *
 * Here a corner's rows that share a run become one rectangle, and the rows of
 * the left and right corners are one rectangle across the shape where they
 * meet, so a filled shape is at most 2r + 1 rectangles and an outline about
 * 4r. Every pixel is covered once, which also keeps translucent colours even
 * (the arc version painted some corner pixels two and three times).
 *
 * The curve is the one the menus already had: the fill's insets are the top
 * corners' old scanlines and the outline's corner is the old top-left arc;
 * the other corners mirror them exactly, where before the bottom fill was one
 * row flatter and the right and bottom arcs sat one pixel outside the box.
 */
public final class RoundRect {

    private RoundRect() {}

    /** Receives the rectangles of a shape, in GUI units, right and bottom edges exclusive. */
    public interface Sink {
        void fill(int x1, int y1, int x2, int y2);
    }

    private static final int CACHED = 64;
    private static final int[][] INSETS = new int[CACHED][];
    private static final int[][] RINGS = new int[CACHED][];

    /** The radius a w by h shape can actually have: corners never overlap. */
    public static int clampRadius(int w, int h, int r) {
        return Math.max(0, Math.min(r, Math.min(w, h) / 2));
    }

    /**
     * How far row i (0 is the outer edge) of a filled corner of radius r starts
     * in from its side, for i from 0 to r - 1. Row r and beyond are full.
     */
    static int[] insets(int r) {
        if (r < CACHED && INSETS[r] != null) return INSETS[r];
        int[] out = new int[r];
        for (int i = 0; i < r; i++) {
            int dy = r - i;
            out[i] = r - (int) Math.sqrt((double) (r * r - dy * dy));
        }
        if (r < CACHED) INSETS[r] = out;
        return out;
    }

    /**
     * A corner's one-unit outline by row: for row i (0 is the outer edge, up to
     * r) the run [from, to) measured in from the side, packed as from * 65536
     * + to; 0 when the row has no point. The points are the old arc's: every
     * two degrees, rounded to the nearest unit.
     */
    static int[] ring(int r) {
        if (r < CACHED && RINGS[r] != null) return RINGS[r];
        int[] from = new int[r + 1];
        int[] to = new int[r + 1];
        java.util.Arrays.fill(from, Integer.MAX_VALUE);
        for (int a = 180; a <= 270; a += 2) {
            double rad = Math.toRadians(a);
            int ox = r + (int) Math.round(Math.cos(rad) * r);
            int oy = r + (int) Math.round(Math.sin(rad) * r);
            if (ox < 0 || oy < 0 || ox > r || oy > r) continue;
            from[oy] = Math.min(from[oy], ox);
            to[oy] = Math.max(to[oy], ox + 1);
        }
        int[] out = new int[r + 1];
        for (int i = 0; i <= r; i++) {
            out[i] = from[i] == Integer.MAX_VALUE ? 0 : (from[i] << 16) | to[i];
        }
        if (r < CACHED) RINGS[r] = out;
        return out;
    }

    /** The filled shape: its rectangles go to {@code out}, nothing when it is empty. */
    public static void fill(int x1, int y1, int x2, int y2, int r, Sink out) {
        int w = x2 - x1, h = y2 - y1;
        if (w <= 0 || h <= 0) return;
        r = clampRadius(w, h, r);
        if (r == 0) {
            out.fill(x1, y1, x2, y2);
            return;
        }
        int[] in = insets(r);
        int runStart = 0;
        int runInset = insetAt(in, r, h, 0);
        for (int y = 1; y <= h; y++) {
            int inset = y < h ? insetAt(in, r, h, y) : -1;
            if (inset == runInset) continue;
            if (w - 2 * runInset > 0) out.fill(x1 + runInset, y1 + runStart, x2 - runInset, y1 + y);
            runStart = y;
            runInset = inset;
        }
    }

    /** Row y's inset, the larger of what the top and the bottom corners ask. */
    private static int insetAt(int[] in, int r, int h, int y) {
        int top = y < r ? in[y] : 0;
        int fromBottom = h - 1 - y;
        int bottom = fromBottom < r ? in[fromBottom] : 0;
        return Math.max(top, bottom);
    }

    /** The one-unit outline along the same curve: its rectangles go to {@code out}. */
    public static void outline(int x1, int y1, int x2, int y2, int r, Sink out) {
        int w = x2 - x1, h = y2 - y1;
        if (w <= 0 || h <= 0) return;
        r = clampRadius(w, h, r);
        if (w <= 2 || h <= 2) {
            out.fill(x1, y1, x2, y2);
            return;
        }
        int[] ring = ring(r);
        // Rows run from top to bottom. A row's left run is [from, to) in from
        // the left side; the right run mirrors it. Runs that repeat over
        // consecutive rows (the straight sides) become one rectangle.
        int runStart = 0;
        int runPacked = leftRun(ring, r, h, w, 0);
        for (int y = 1; y <= h; y++) {
            int packed = y < h ? leftRun(ring, r, h, w, y) : -1;
            if (packed == runPacked) continue;
            emitRow(x1, x2, y1 + runStart, y1 + y, runPacked, w, out);
            runStart = y;
            runPacked = packed;
        }
    }

    /**
     * The left run of row y, packed as from * 65536 + to. The top and bottom
     * rows are whole edges (to = w / 2 makes them one rectangle across); a row
     * both corners reach takes the union of their runs; a row neither reaches
     * is the straight side, one unit wide.
     */
    private static int leftRun(int[] ring, int r, int h, int w, int y) {
        int fromBottom = h - 1 - y;
        int top = y <= r ? ring[y] : 0;
        int bottom = fromBottom <= r ? ring[fromBottom] : 0;
        int run;
        if (top != 0 && bottom != 0) {
            run = (Math.min(top >>> 16, bottom >>> 16) << 16) | Math.max(top & 0xFFFF, bottom & 0xFFFF);
        } else if (top != 0) {
            run = top;
        } else if (bottom != 0) {
            run = bottom;
        } else {
            run = 1; // from 0 to 1
        }
        if (y == 0 || y == h - 1) {
            // The edge itself: from the corner's first point across the middle.
            int from = r == 0 ? 0 : run >>> 16;
            return (from << 16) | (w + 1) / 2;
        }
        return run;
    }

    private static void emitRow(int x1, int x2, int ya, int yb, int packed, int w, Sink out) {
        int from = packed >>> 16;
        int to = Math.min(packed & 0xFFFF, (w + 1) / 2);
        if (to <= from) return;
        if (2 * to >= w) {
            // The two runs meet: one rectangle across.
            out.fill(x1 + from, ya, x2 - from, yb);
            return;
        }
        out.fill(x1 + from, ya, x1 + to, yb);
        out.fill(x2 - to, ya, x2 - from, yb);
    }
}
