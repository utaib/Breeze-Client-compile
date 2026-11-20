package dev.breeze.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PositionPredictorTest {

    /** A player walking +x at 5.6 blocks a second, one position every tick (50 ms). */
    private static PositionPredictor walking(int ticks) {
        PositionPredictor p = new PositionPredictor();
        for (int i = 0; i < ticks; i++) p.observe(i * 50L, i * 0.28, 64, 0);
        return p;
    }

    @Test
    void nothingObservedIsNotReady() {
        assertFalse(new PositionPredictor().ready());
    }

    @Test
    void aSteadyWalkIsCarriedOnByTheLead() {
        PositionPredictor p = walking(20);
        assertTrue(p.ready());
        assertEquals(5.6, p.speed(), 0.01);
        long last = 19 * 50L;
        double[] now = p.predict(last, 0);
        assertEquals(19 * 0.28, now[0], 1e-6, "no lead, no time passed: where the server said");
        double[] ahead = p.predict(last + 20, 100);
        assertEquals(19 * 0.28 + 0.0056 * 120, ahead[0], 1e-4, "time since arrival plus the lead");
        assertEquals(64, ahead[1], 1e-9);
    }

    @Test
    void theSameFrameSeenAgainIsNotANewSample() {
        PositionPredictor p = walking(10);
        double before = p.speed();
        p.observe(9 * 50L + 16, 9 * 0.28, 64, 0);
        p.observe(9 * 50L + 32, 9 * 0.28, 64, 0);
        assertEquals(before, p.speed(), 1e-12);
    }

    @Test
    void aPlayerWhoStoppedIsNotMovedOn() {
        PositionPredictor p = walking(10);
        long last = 9 * 50L;
        double[] later = p.predict(last + PositionPredictor.STILL_MS + 1, 200);
        assertArrayEquals(new double[] {9 * 0.28, 64, 0}, later, 1e-9);
    }

    @Test
    void theLeadIsCapped() {
        PositionPredictor p = walking(20);
        long last = 19 * 50L;
        double[] far = p.predict(last, 10_000);
        assertEquals(19 * 0.28 + 0.0056 * PositionPredictor.MAX_LEAD_MS, far[0], 1e-4);
    }

    @Test
    void aTeleportIsNotMotion() {
        PositionPredictor p = walking(10);
        p.observe(500, 1000, 64, 1000);
        assertEquals(0.0, p.speed(), 1e-12);
        assertArrayEquals(new double[] {1000, 64, 1000}, p.predict(510, 100), 1e-9);
    }

    @Test
    void startingToMoveAfterStandingStillWaitsForASecondStep() {
        PositionPredictor p = new PositionPredictor();
        p.observe(0, 0, 64, 0);
        p.observe(5_000, 0.28, 64, 0);
        assertEquals(0.0, p.speed(), 1e-12, "five seconds between samples says nothing about speed");
        p.observe(5_050, 0.56, 64, 0);
        assertTrue(p.speed() > 0);
    }
}
