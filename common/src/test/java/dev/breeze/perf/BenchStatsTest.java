package dev.breeze.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BenchStatsTest {

    private static long ms(double v) {
        return (long) (v * 1_000_000);
    }

    @Test
    void nothingMeasuredIsNothing() {
        assertNull(BenchStats.of(null, 0));
        assertNull(BenchStats.of(new long[4], 0));
    }

    @Test
    void steadyFramesAreTheSameEverywhere() {
        long[] frames = new long[1000];
        java.util.Arrays.fill(frames, ms(5));
        BenchStats s = BenchStats.of(frames, frames.length);
        assertEquals(1000, s.frames);
        assertEquals(5.0, s.seconds, 1e-9);
        assertEquals(200.0, s.avgFps, 1e-6);
        assertEquals(200.0, s.low1Fps, 1e-6);
        assertEquals(200.0, s.low01Fps, 1e-6);
        assertEquals(5.0, s.medianMs, 1e-9);
        assertEquals("200 fps average, 1% low 200, 0.1% low 200", s.summary());
    }

    @Test
    void stuttersShowInTheLowsNotTheAverage() {
        // 990 frames of 4 ms and ten of 50 ms: the average barely moves.
        long[] frames = new long[1000];
        java.util.Arrays.fill(frames, ms(4));
        for (int i = 0; i < 10; i++) frames[i * 100] = ms(50);
        BenchStats s = BenchStats.of(frames, frames.length);
        assertEquals(1000 / (990 * 0.004 + 10 * 0.050), s.avgFps, 1e-6);
        assertTrue(s.avgFps > 220, "average " + s.avgFps);
        assertEquals(20.0, s.low1Fps, 1e-6, "the slowest hundredth is the ten stutters");
        assertEquals(20.0, s.low01Fps, 1e-6);
        assertEquals(20.0, s.minFps, 1e-6);
        assertEquals(250.0, s.maxFps, 1e-6);
        assertEquals(4.0, s.medianMs, 1e-9);
    }

    @Test
    void onlyTheFramesCountedAreUsed() {
        long[] frames = {ms(10), ms(10), ms(1000), 0, 0};
        BenchStats two = BenchStats.of(frames, 2);
        assertEquals(2, two.frames);
        assertEquals(100.0, two.avgFps, 1e-6);
        // A few frames: the lows are the one slowest frame.
        BenchStats three = BenchStats.of(frames, 3);
        assertEquals(1.0, three.low1Fps, 1e-6);
        // More asked for than there are: as many as there are.
        assertEquals(5, BenchStats.of(frames, 99).frames);
    }

    @Test
    void comparedWithTheRunBefore() {
        long[] frames = new long[100];
        java.util.Arrays.fill(frames, ms(5));
        BenchStats s = BenchStats.of(frames, frames.length);
        assertEquals("", s.against(0));
        assertEquals("25% faster than the run before (160 fps)", s.against(160));
        assertEquals("20% slower than the run before (250 fps)", s.against(250));
        assertEquals("the same as the run before (201 fps)", s.against(201));
    }

    @Test
    void theNumbersAsJson() {
        long[] frames = new long[200];
        java.util.Arrays.fill(frames, ms(5));
        String json = "{" + BenchStats.of(frames, frames.length).jsonFields() + "}";
        assertEquals("{\"frames\":200,\"seconds\":1.0,\"avgFps\":200.0,\"low1Fps\":200.0,\"low01Fps\":200.0,"
                + "\"minFps\":200.0,\"maxFps\":200.0,\"medianMs\":5.00}", json);
    }
}
