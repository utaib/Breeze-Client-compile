package dev.breeze.hud;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a HUD element sits, independent of the screen size.
 *
 * An element is stored as an anchor (which third of the screen it belongs to,
 * horizontally and vertically) and an offset from that anchor's edge or centre.
 * An element placed against the right edge stays against the right edge when
 * the window or the GUI scale changes, instead of keeping an absolute x that
 * may now be off screen. Whatever the stored offset, the element is kept wholly
 * on screen when it is drawn, so it can always be seen and picked up again.
 *
 * Pure arithmetic in GUI pixels, so it is tested without Minecraft.
 */
public final class HudPlacement {

    public enum H { LEFT, CENTER, RIGHT }

    public enum V { TOP, MIDDLE, BOTTOM }

    public final H h;
    public final V v;
    /** Offset from the anchor: from the left edge, the centre line, or the right edge. */
    public final int dx;
    /** Offset from the anchor: from the top edge, the middle line, or the bottom edge. */
    public final int dy;

    public HudPlacement(H h, V v, int dx, int dy) {
        this.h = h;
        this.v = v;
        this.dx = dx;
        this.dy = dy;
    }

    /** A top-left anchored placement, the form every stored position had before anchors. */
    public static HudPlacement topLeft(int x, int y) {
        return new HudPlacement(H.LEFT, V.TOP, x, y);
    }

    /**
     * The placement for an element drawn at (x, y) with size w by h on a screen
     * sw by sh: anchored to the third of the screen its centre is in.
     */
    public static HudPlacement of(int x, int y, int w, int h, int sw, int sh) {
        int cx = x + w / 2;
        int cy = y + h / 2;
        H ha = cx < sw / 3 ? H.LEFT : cx > sw - sw / 3 ? H.RIGHT : H.CENTER;
        V va = cy < sh / 3 ? V.TOP : cy > sh - sh / 3 ? V.BOTTOM : V.MIDDLE;
        int dx = switch (ha) {
            case LEFT -> x;
            case CENTER -> cx - sw / 2;
            case RIGHT -> sw - (x + w);
        };
        int dy = switch (va) {
            case TOP -> y;
            case MIDDLE -> cy - sh / 2;
            case BOTTOM -> sh - (y + h);
        };
        return new HudPlacement(ha, va, dx, dy);
    }

    /** Top-left corner {x, y} for an element of size w by h on a screen sw by sh, kept on screen. */
    public int[] resolve(int w, int hgt, int sw, int sh) {
        int x = switch (h) {
            case LEFT -> dx;
            case CENTER -> sw / 2 + dx - w / 2;
            case RIGHT -> sw - w - dx;
        };
        int y = switch (v) {
            case TOP -> dy;
            case MIDDLE -> sh / 2 + dy - hgt / 2;
            case BOTTOM -> sh - hgt - dy;
        };
        return clamp(x, y, w, hgt, sw, sh);
    }

    /** Keeps an element of size w by h wholly on a screen sw by sh (top-left wins if it is larger). */
    public static int[] clamp(int x, int y, int w, int h, int sw, int sh) {
        int cx = Math.max(0, Math.min(x, sw - w));
        int cy = Math.max(0, Math.min(y, sh - h));
        return new int[]{cx, cy};
    }

    // ── Snapping ─────────────────────────────────────────────────────────

    /** An element's box, for snapping against. */
    public record Box(int x, int y, int w, int h) {}

    /**
     * Where a dragged element ends up and which guide lines to show: its left,
     * centre or right edge snaps to the screen's edges and centre line, or to
     * another element's edges and centre, whichever is nearest within
     * {@code threshold} pixels; the same vertically. Coordinates are GUI pixels.
     */
    public record Snap(int x, int y, List<Integer> guidesX, List<Integer> guidesY) {}

    public static Snap snap(int x, int y, int w, int h, int sw, int sh, List<Box> others, int threshold) {
        List<Integer> targetsX = new ArrayList<>(List.of(0, sw / 2, sw));
        List<Integer> targetsY = new ArrayList<>(List.of(0, sh / 2, sh));
        for (Box b : others) {
            targetsX.add(b.x());
            targetsX.add(b.x() + b.w() / 2);
            targetsX.add(b.x() + b.w());
            targetsY.add(b.y());
            targetsY.add(b.y() + b.h() / 2);
            targetsY.add(b.y() + b.h());
        }
        int[] sx = nearest(new int[]{x, x + w / 2, x + w}, targetsX, threshold);
        int[] sy = nearest(new int[]{y, y + h / 2, y + h}, targetsY, threshold);
        List<Integer> gx = new ArrayList<>();
        List<Integer> gy = new ArrayList<>();
        if (sx != null) gx.add(sx[1]);
        if (sy != null) gy.add(sy[1]);
        int[] c = clamp(x + (sx == null ? 0 : sx[0]), y + (sy == null ? 0 : sy[0]), w, h, sw, sh);
        return new Snap(c[0], c[1], gx, gy);
    }

    /** {shift, target} for the edge closest to a target within threshold, or null. */
    private static int[] nearest(int[] edges, List<Integer> targets, int threshold) {
        int[] best = null;
        for (int e : edges) {
            for (int t : targets) {
                int d = t - e;
                if (Math.abs(d) <= threshold && (best == null || Math.abs(d) < Math.abs(best[0]))) {
                    best = new int[]{d, t};
                }
            }
        }
        return best;
    }

    // ── Arranging ────────────────────────────────────────────────────────

    /**
     * Top-left corners that stack the given sizes down the left edge from
     * (margin, margin) with {@code gap} between them, starting a new column to
     * the right when one would run past the bottom. Used to lay out overlapping
     * elements in one step.
     */
    public static List<int[]> stack(List<int[]> sizes, int sh, int margin, int gap) {
        List<int[]> out = new ArrayList<>();
        int x = margin;
        int y = margin;
        int columnW = 0;
        for (int[] s : sizes) {
            int w = s[0];
            int h = s[1];
            if (y > margin && y + h > sh - margin) {
                x += columnW + gap;
                y = margin;
                columnW = 0;
            }
            out.add(new int[]{x, y});
            y += h + gap;
            columnW = Math.max(columnW, w);
        }
        return out;
    }

    // ── Storage ──────────────────────────────────────────────────────────

    /** "LEFT,TOP,4,24": the stored form. */
    public String encode() {
        return h + "," + v + "," + dx + "," + dy;
    }

    /** The stored form back, or null if it is not one. */
    public static HudPlacement decode(String s) {
        if (s == null) return null;
        String[] p = s.split(",");
        if (p.length != 4) return null;
        try {
            return new HudPlacement(H.valueOf(p[0].trim()), V.valueOf(p[1].trim()),
                    Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HudPlacement p && p.h == h && p.v == v && p.dx == dx && p.dy == dy;
    }

    @Override
    public int hashCode() {
        return encode().hashCode();
    }

    @Override
    public String toString() {
        return encode();
    }
}
