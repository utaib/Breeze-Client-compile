package dev.breeze.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GhostModelTest {

    private static double[] bounds(float[] pts) {
        double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (int i = 0; i < pts.length; i += 3) {
            for (int a = 0; a < 3; a++) {
                b[a] = Math.min(b[a], pts[i + a]);
                b[a + 3] = Math.max(b[a + 3], pts[i + a]);
            }
        }
        return b;
    }

    @Test
    void sixBoxesStandingOnTheFeetAsTallAsThePlayer() {
        GhostModel.Figure f = GhostModel.build(10, 64, -3, 0, 0, 0, 1.8);
        assertEquals(36, f.quadCount(), "six boxes, six faces each");
        assertEquals(72, f.edgeCount(), "six boxes, twelve edges each");
        double[] b = bounds(f.faces);
        assertEquals(64.0, b[1], 1e-4, "feet on the ground");
        assertEquals(65.8, b[4], 1e-4, "head at the player's height");
        // Arms out to 8 pixels each side: 16 of 32 pixels is half the height.
        assertEquals(0.9, b[3] - b[0], 1e-4);
    }

    @Test
    void facingWestTurnsTheShouldersToRunNorthSouth() {
        GhostModel.Figure south = GhostModel.build(0, 0, 0, 0, 0, 0, 1.8);
        GhostModel.Figure west = GhostModel.build(0, 0, 0, 90, 90, 0, 1.8);
        double[] s = bounds(south.faces);
        double[] w = bounds(west.faces);
        assertEquals(s[3] - s[0], w[5] - w[2], 1e-4, "width across x becomes width across z");
    }

    @Test
    void theHeadLooksTheWayThePlayerLooks() {
        // Yaw 0 faces +z. The head's front face is 4 pixels ahead of the neck.
        double[] front = GhostModel.yaw(0, 4, 0);
        assertEquals(0, front[0], 1e-9);
        assertEquals(4, front[1], 1e-9);
        double[] west = GhostModel.yaw(0, 4, 90);
        assertEquals(-4, west[0], 1e-9, "yaw 90 faces west");
        // Looking straight down moves the top of the head forward.
        double[] top = GhostModel.pitch(0, 32, 0, 90);
        assertEquals(24, top[1], 1e-9);
        assertEquals(8, top[2], 1e-9);
    }

    @Test
    void aCrouchingPlayerIsShorter() {
        double[] stand = bounds(GhostModel.build(0, 0, 0, 0, 0, 0, 1.8).faces);
        double[] crouch = bounds(GhostModel.build(0, 0, 0, 0, 0, 0, 1.5).faces);
        assertTrue(crouch[4] < stand[4]);
    }
}
