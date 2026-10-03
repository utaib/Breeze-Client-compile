package dev.breeze.cosmetics.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CosmeticMotionTest {

    @Test
    void flyingPetOrbitsNearItsRadiusAndHeight() {
        CosmeticMotion.Flying f = new CosmeticMotion.Flying(12, 8, -3);
        float maxR = 0, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        boolean sawIdle = false, sawFly = false;
        for (int i = 0; i < 60 * 60; i++) { // a minute at 60 frames a second
            String s = f.step(1 / 60f);
            sawIdle |= s.equals("idle");
            sawFly |= s.equals("fly");
            if (i > 600) {
                maxR = Math.max(maxR, (float) Math.hypot(f.pos[0], f.pos[2]));
                minY = Math.min(minY, f.pos[1]);
                maxY = Math.max(maxY, f.pos[1]);
            }
        }
        float radius = (float) Math.hypot(12, -3);
        assertTrue(maxR < radius * 1.3f, "stays near its circle, max " + maxR);
        assertTrue(minY > 8 - 5 && maxY < 8 + 5, "bobs around its height: " + minY + ".." + maxY);
        assertTrue(sawFly && sawIdle, "flies and stops to hover");
        assertTrue(Math.abs(f.roll) <= 0.5f);
    }

    @Test
    void trailLeavesCopiesThatAgeOutAndDriftWithSpeed() {
        CosmeticMotion.Trail t = new CosmeticMotion.Trail(42);
        for (int i = 0; i < 120; i++) t.step(1 / 60f, 0, -11, -4, 16);
        // One copy every 0.14 s, each living 1.4 s: about ten at a time.
        assertTrue(t.copies.size() >= 9 && t.copies.size() <= 11, "copies: " + t.copies.size());
        CosmeticMotion.Copy oldest = t.copies.get(0);
        assertTrue(oldest.z < -4 - 10, "the oldest drifted back behind the player");
        assertTrue(oldest.scale() < 1 && oldest.opacity() < 1);

        CosmeticMotion.Trail still = new CosmeticMotion.Trail(42);
        for (int i = 0; i < 120; i++) still.step(1 / 60f, 0, -11, -4, 0);
        for (CosmeticMotion.Copy c : still.copies) assertEquals(-4f, c.z, 1e-4f, "no drift standing still");
    }
}
