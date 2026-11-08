package dev.breeze.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CountdownTest {

    @Test
    void fuseTimeIsSecondsAndMilliseconds() {
        assertEquals("3.250s", Countdown.label(3.25, false));
        assertEquals("0.050s", Countdown.label(0.05, false));
        assertEquals("0.005s", Countdown.label(0.0049, false));
        assertEquals("10.000s", Countdown.label(9.99999, false));
        assertEquals("0.000s", Countdown.label(-1, false), "never negative");
        assertEquals("0.000s", Countdown.label(Double.NaN, false));
        assertEquals("3.3s", Countdown.label(3.25, true), "tenths when chosen");
    }

    @Test
    void labelsGrowWithDistanceWithinLimits() {
        assertEquals(1.0f, Countdown.size(2, true, 100), "near: normal size");
        assertEquals(1.0f, Countdown.size(8, true, 100));
        assertEquals(2.0f, Countdown.size(16, true, 100), 1e-6);
        assertEquals(3.0f, Countdown.size(24, true, 100), 1e-6);
        assertEquals(3.0f, Countdown.size(200, true, 100), 1e-6, "never more than three times");
        assertEquals(1.0f, Countdown.size(40, false, 100), "growing off");
        assertEquals(0.5f, Countdown.size(2, true, 10), 1e-6, "size kept to 50 % or more");
        assertEquals(4.0f, Countdown.size(16, true, 500), 1e-6, "size kept to 200 % or less");
    }
}
