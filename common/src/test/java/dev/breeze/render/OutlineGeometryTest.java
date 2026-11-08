package dev.breeze.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OutlineGeometryTest {

    private static double len(double x, double y, double z) {
        return Math.sqrt(x * x + y * y + z * z);
    }

    @Test
    void aBoxHasTwelveEdgesOfItsSidesLengths() {
        float[] e = OutlineGeometry.boxEdges(0, 0, 0, 1, 2, 3);
        assertEquals(72, e.length);
        int ones = 0, twos = 0, threes = 0;
        for (int i = 0; i < 12; i++) {
            int s = i * 6;
            double l = len(e[s + 3] - e[s], e[s + 4] - e[s + 1], e[s + 5] - e[s + 2]);
            if (Math.abs(l - 1) < 1e-6) ones++;
            else if (Math.abs(l - 2) < 1e-6) twos++;
            else if (Math.abs(l - 3) < 1e-6) threes++;
        }
        assertEquals(4, ones);
        assertEquals(4, twos);
        assertEquals(4, threes);
    }

    @Test
    void aBoxHasSixFlatFaces() {
        float[] f = OutlineGeometry.boxFaces(-1, -1, -1, 1, 1, 1);
        assertEquals(72, f.length);
        for (int q = 0; q < 6; q++) {
            // Each face lies in one plane: one coordinate is the same at all four corners.
            int same = 0;
            for (int axis = 0; axis < 3; axis++) {
                float v = f[q * 12 + axis];
                boolean all = true;
                for (int c = 1; c < 4; c++) all &= f[q * 12 + c * 3 + axis] == v;
                if (all) same++;
            }
            assertEquals(1, same, "face " + q);
        }
    }

    @Test
    void pixelsAtOneBlockFollowTheScreenAndTheFov() {
        double p = OutlineGeometry.pixelsAtOneBlock(1080, 70);
        assertEquals(1080 / (2 * Math.tan(Math.toRadians(35))), p, 1e-9);
        assertTrue(OutlineGeometry.pixelsAtOneBlock(1080, 30) > p, "a narrower view makes everything bigger");
    }

    @Test
    void aStripIsAsWideOnScreenAsAskedAtAnyDistance() {
        double ppb = OutlineGeometry.pixelsAtOneBlock(1080, 70);
        for (double d : new double[] {2, 8, 40}) {
            // A segment across the view, d blocks ahead (the camera looks along +z here).
            // Off to one side too, where straight-line distance and depth differ.
            float[] seg = {(float) (d * 0.4), 0, (float) d, (float) (d * 0.4 + 0.5), 0, (float) d};
            float[] q = OutlineGeometry.strips(seg, 1, 4, ppb, 0, 0, 1);
            assertEquals(12, q.length);
            // Points 0 and 1 are the two sides of the first end.
            double wx = q[0] - q[3], wy = q[1] - q[4], wz = q[2] - q[5];
            double world = len(wx, wy, wz);
            double depth = (q[2] + q[5]) / 2.0;
            double pixels = world * ppb / depth;
            assertEquals(4.0, pixels, 0.05, "width on screen at distance " + d);
        }
    }

    @Test
    void aStripComesTowardsTheCameraWithoutMovingOnScreen() {
        double ppb = OutlineGeometry.pixelsAtOneBlock(1080, 70);
        float[] seg = {0, -1, 5, 0, 1, 5};
        float[] q = OutlineGeometry.strips(seg, 1, 6, ppb, 0, 0, 1);
        for (int c = 0; c < 4; c++) {
            assertTrue(q[c * 3 + 2] < 5.0f, "corner " + c + " is nearer than the segment");
        }
        // The strip's middle still looks at the segment's middle: same direction from the camera.
        double mx = 0, my = 0, mz = 0;
        for (int c = 0; c < 4; c++) {
            mx += q[c * 3];
            my += q[c * 3 + 1];
            mz += q[c * 3 + 2];
        }
        assertEquals(0.0, mx / mz, 1e-6);
        assertEquals(0.0, my / mz, 1e-3);
    }

    @Test
    void aStripReachesPastTheEndsSoCornersMeet() {
        double ppb = OutlineGeometry.pixelsAtOneBlock(1080, 70);
        float[] seg = {-1, 0, 4, 1, 0, 4};
        float[] q = OutlineGeometry.strips(seg, 1, 8, ppb, 0, 0, 1);
        assertTrue(q[0] < -1.0f && q[6] > 1.0f, "ends extended");
    }

    @Test
    void aSegmentSeenEndOnOrOfNoLengthStillGivesFourPoints() {
        double ppb = OutlineGeometry.pixelsAtOneBlock(1080, 70);
        float[] endOn = OutlineGeometry.strips(new float[] {0, 0, 2, 0, 0, 3}, 1, 3, ppb, 0, 0, 1);
        float[] none = OutlineGeometry.strips(new float[] {1, 1, 1, 1, 1, 1}, 1, 3, ppb, 0, 0, 1);
        for (float v : endOn) assertTrue(Float.isFinite(v));
        for (float v : none) assertTrue(Float.isFinite(v));
    }

    @Test
    void rainbowKeepsTheAlphaAndChangesOverTime() {
        int a = OutlineGeometry.rainbow(0, 0x80);
        int b = OutlineGeometry.rainbow(2000, 0x80);
        assertEquals(0x80, a >>> 24);
        assertEquals(0x80, b >>> 24);
        assertTrue((a & 0xFFFFFF) != (b & 0xFFFFFF));
        assertEquals(OutlineGeometry.rainbow(0, 255), OutlineGeometry.rainbow(4000, 255), "one cycle is four seconds");
    }

    @Test
    void rgbaSplitsTheChannels() {
        float[] c = OutlineGeometry.rgba(0x80FF0040);
        assertEquals(1f, c[0], 1e-6);
        assertEquals(0f, c[1], 1e-6);
        assertEquals(0x40 / 255f, c[2], 1e-6);
        assertEquals(0x80 / 255f, c[3], 1e-6);
    }
}
