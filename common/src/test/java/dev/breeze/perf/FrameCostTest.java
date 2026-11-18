package dev.breeze.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FrameCostTest {

    @AfterEach
    void off() {
        FrameCost.enable(false);
        FrameCost.reset();
    }

    @Test
    void offUntilAsked() {
        assertFalse(FrameCost.enabled());
        assertEquals(0, FrameCost.total(FrameCost.HUD).calls);
        assertEquals(0.0, FrameCost.total(FrameCost.HUD).microsPerCall());
    }

    @Test
    void countsCallsTimeAndMemoryPerSection() {
        FrameCost.enable(true);
        for (int i = 0; i < 3; i++) {
            long t = System.nanoTime(), b = FrameCost.allocated();
            byte[] work = new byte[64 * 1024];
            work[i] = 1;
            FrameCost.section(FrameCost.HUD, t, b);
        }
        FrameCost.Total hud = FrameCost.total(FrameCost.HUD);
        assertEquals(3, hud.calls);
        assertTrue(hud.nanos > 0);
        assertTrue(hud.maxNanos <= hud.nanos);
        // Where the runtime counts allocations (HotSpot does), three 64 KB
        // arrays were allocated inside the measured part.
        if (FrameCost.allocated() > 0) assertTrue(hud.bytesPerCall() >= 64 * 1024, "bytes " + hud.bytesPerCall());
        assertEquals(0, FrameCost.total(FrameCost.TICK).calls);
    }

    @Test
    void ranksModulesByTimeAndByMemory() {
        // The first reads of the allocation counter can be slow on a cold
        // runtime (a build machine took over 5 ms for "Cheap"): warm it up.
        for (int i = 0; i < 1000; i++) FrameCost.allocated();
        FrameCost.enable(true);
        long t = System.nanoTime(), b = FrameCost.allocated();
        FrameCost.module(FrameCost.HUD, "Cheap", t, b);
        t = System.nanoTime() - 500_000_000;
        b = FrameCost.allocated();
        FrameCost.module(FrameCost.HUD, "Slow", t, b);
        t = System.nanoTime();
        b = FrameCost.allocated() - 1_000_000;
        FrameCost.module(FrameCost.HUD, "Hungry", t, b);
        FrameCost.module(FrameCost.TICK, "Elsewhere", System.nanoTime() - 900_000_000, FrameCost.allocated());

        List<String> byTime = FrameCost.top(FrameCost.HUD, false, 2);
        assertEquals(2, byTime.size());
        assertTrue(byTime.get(0).startsWith("Slow "), byTime.toString());
        assertEquals(1, FrameCost.moduleTotal(FrameCost.HUD, "Slow").calls);
        assertTrue(FrameCost.moduleTotal(FrameCost.HUD, "Slow").microsPerCall() >= 500_000);
        assertEquals(0, FrameCost.moduleTotal(FrameCost.HUD, "Elsewhere").calls);
        if (FrameCost.allocated() > 0) {
            List<String> byBytes = FrameCost.top(FrameCost.HUD, true, 3);
            assertTrue(byBytes.get(0).startsWith("Hungry "), byBytes.toString());
        }
    }

    @Test
    void startingClearsWhatWasMeasured() {
        FrameCost.enable(true);
        FrameCost.section(FrameCost.TICK, System.nanoTime(), FrameCost.allocated());
        assertEquals(1, FrameCost.total(FrameCost.TICK).calls);
        FrameCost.enable(true);
        assertEquals(0, FrameCost.total(FrameCost.TICK).calls);
        assertTrue(FrameCost.enabled());
    }

    @Test
    void formatsWithADotInAnyLocale() {
        assertEquals("12.3", FrameCost.format(12.34));
    }
}
