package dev.breeze.ui;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoundRectTest {

    /** Pixel coverage counts of a shape drawn into a w by h grid, plus how many rectangles it took. */
    private static final class Raster {
        final int[][] count;
        int calls;
        boolean outside;

        Raster(int w, int h) {
            count = new int[Math.max(0, h)][Math.max(0, w)];
        }

        RoundRect.Sink sink() {
            return (x1, y1, x2, y2) -> {
                calls++;
                assertTrue(x2 > x1 && y2 > y1, "empty rectangle " + x1 + "," + y1 + " " + x2 + "," + y2);
                for (int y = y1; y < y2; y++) {
                    for (int x = x1; x < x2; x++) {
                        if (y < 0 || x < 0 || y >= count.length || x >= count[0].length) {
                            outside = true;
                            continue;
                        }
                        count[y][x]++;
                    }
                }
            };
        }
    }

    private static Raster fill(int w, int h, int r) {
        Raster ras = new Raster(w, h);
        RoundRect.fill(0, 0, w, h, r, ras.sink());
        return ras;
    }

    private static Raster outline(int w, int h, int r) {
        Raster ras = new Raster(w, h);
        RoundRect.outline(0, 0, w, h, r, ras.sink());
        return ras;
    }

    private static void assertCoveredAtMostOnce(Raster ras, String what) {
        assertTrue(!ras.outside, what + ": painted outside its box");
        for (int[] row : ras.count) {
            for (int c : row) assertTrue(c <= 1, what + ": a pixel painted " + c + " times");
        }
    }

    private static void assertSymmetric(Raster ras, String what) {
        int h = ras.count.length, w = ras.count[0].length;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = ras.count[y][x];
                assertEquals(c, ras.count[y][w - 1 - x], what + ": not mirrored left to right at " + x + "," + y);
                assertEquals(c, ras.count[h - 1 - y][x], what + ": not mirrored top to bottom at " + x + "," + y);
            }
        }
    }

    /** The old Glass.fillRounded top-left quadrant: one scanline per row, as 2.24.0 drew it. */
    private static Set<Long> oldFillTopLeft(int r) {
        Set<Long> px = new HashSet<>();
        int cx = r, cy = r;
        for (int dy = 0; dy <= r; dy++) {
            int dx = (int) Math.sqrt((double) (r * r - dy * dy));
            for (int x = cx - dx; x < cx; x++) px.add(((long) (cy - dy) << 32) | x);
        }
        return px;
    }

    /** The old Glass.roundedBorder top-left arc: a point every two degrees. */
    private static Set<Long> oldArcTopLeft(int r) {
        Set<Long> px = new HashSet<>();
        for (int a = 180; a <= 270; a += 2) {
            double rad = Math.toRadians(a);
            int x = r + (int) Math.round(Math.cos(rad) * r);
            int y = r + (int) Math.round(Math.sin(rad) * r);
            px.add(((long) y << 32) | x);
        }
        return px;
    }

    @Test
    void fillCoversEachPixelOnceAndMirrors() {
        for (int r = 0; r <= 12; r++) {
            for (int[] size : new int[][]{{40, 30}, {26, 12}, {12, 12}, {7, 9}, {120, 46}, {3, 50}}) {
                Raster ras = fill(size[0], size[1], r);
                String what = "fill " + size[0] + "x" + size[1] + " r" + r;
                assertCoveredAtMostOnce(ras, what);
                assertSymmetric(ras, what);
                int rr = RoundRect.clampRadius(size[0], size[1], r);
                assertTrue(ras.calls <= 2 * rr + 1, what + ": " + ras.calls + " rectangles");
            }
        }
    }

    @Test
    void fillKeepsTheMenusTopLeftCurve() {
        for (int r = 1; r <= 10; r++) {
            int w = 4 * r + 6, h = 4 * r + 4;
            Raster ras = fill(w, h, r);
            // Inside the top-left r by r square the old shape was its quadrant
            // alone (the body started at x = r and y = r), so a pixel there is
            // painted exactly when the old quadrant painted it.
            Set<Long> old = oldFillTopLeft(r);
            for (int y = 0; y < r; y++) {
                for (int x = 0; x < r; x++) {
                    boolean was = old.contains(((long) y << 32) | x);
                    assertEquals(was ? 1 : 0, ras.count[y][x], "r" + r + " at " + x + "," + y);
                }
            }
        }
    }

    @Test
    void fillOfARectangleWithoutRadiusIsOneRectangle() {
        Raster ras = fill(30, 20, 0);
        assertEquals(1, ras.calls);
        for (int[] row : ras.count) for (int c : row) assertEquals(1, c);
    }

    @Test
    void emptyShapesDrawNothing() {
        assertEquals(0, fill(0, 10, 4).calls);
        assertEquals(0, fill(10, -3, 4).calls);
        assertEquals(0, outline(0, 10, 4).calls);
        assertEquals(0, outline(10, 0, 4).calls);
    }

    @Test
    void outlineCoversEachPixelOnceInsideItsBox() {
        for (int r = 0; r <= 12; r++) {
            for (int[] size : new int[][]{{40, 30}, {26, 12}, {12, 12}, {8, 8}, {120, 46}, {9, 31}}) {
                Raster ras = outline(size[0], size[1], r);
                String what = "outline " + size[0] + "x" + size[1] + " r" + r;
                assertCoveredAtMostOnce(ras, what);
                assertSymmetric(ras, what);
                int rr = RoundRect.clampRadius(size[0], size[1], r);
                assertTrue(ras.calls <= 4 * rr + 4, what + ": " + ras.calls + " rectangles");
            }
        }
    }

    @Test
    void outlineKeepsTheMenusTopLeftArcAndClosesTheEdges() {
        for (int r = 1; r <= 10; r++) {
            int w = 4 * r + 10, h = 4 * r + 8;
            Raster ras = outline(w, h, r);
            Set<Long> arc = oldArcTopLeft(r);
            for (int y = 0; y <= r; y++) {
                for (int x = 0; x <= r; x++) {
                    boolean was = arc.contains(((long) y << 32) | x);
                    assertEquals(was ? 1 : 0, ras.count[y][x], "r" + r + " arc at " + x + "," + y);
                }
            }
            // The straight edges are solid between the corners.
            for (int x = r; x < w - r; x++) {
                assertEquals(1, ras.count[0][x], "top edge r" + r + " at " + x);
                assertEquals(1, ras.count[h - 1][x], "bottom edge r" + r + " at " + x);
            }
            for (int y = r; y < h - r; y++) {
                assertEquals(1, ras.count[y][0], "left edge r" + r + " at " + y);
                assertEquals(1, ras.count[y][w - 1], "right edge r" + r + " at " + y);
            }
            // Nothing inside the ring.
            for (int y = r + 1; y < h - r - 1; y++) {
                for (int x = r + 1; x < w - r - 1; x++) {
                    assertEquals(0, ras.count[y][x], "inside r" + r + " at " + x + "," + y);
                }
            }
        }
    }

    @Test
    void aPillOutlineIsClosed() {
        // The toggle: 26 by 12 with radius 6, both corners meeting in the middle rows.
        Raster ras = outline(26, 12, 6);
        for (int y = 0; y < 12; y++) {
            assertTrue(ras.count[y][0] + ras.count[y][1] + ras.count[y][2] + ras.count[y][3] + ras.count[y][4] + ras.count[y][5] + ras.count[y][6] >= 1,
                    "left side open at row " + y);
        }
    }

    @Test
    void theMenuCardCostsAFractionOfWhatItDid() {
        // A module card (r4), its toggle (pill r6 with an r4 knob): 2.24.0 used
        // 23 + 188 + 31 + 188 + 23 fills for these.
        int calls = fill(120, 46, 4).calls + outline(120, 46, 4).calls
                + fill(26, 12, 6).calls + outline(26, 12, 6).calls + fill(8, 8, 4).calls;
        assertTrue(calls <= 80, calls + " rectangles for a card and its toggle");
    }
}
