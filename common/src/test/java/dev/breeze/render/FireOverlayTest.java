package dev.breeze.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FireOverlayTest {

    @Test
    void zeroIsTheVanillaOverlay() {
        assertEquals(0f, FireOverlay.drop(0));
        assertFalse(FireOverlay.hidden(0));
    }

    @Test
    void theSliderMapsLinearlyOntoTheDrop() {
        assertEquals(FireOverlay.FULL_DROP / 2, FireOverlay.drop(50), 1e-6);
        assertTrue(FireOverlay.drop(30) < FireOverlay.drop(60));
    }

    @Test
    void oneHundredHidesItLikeTheOldModule() {
        assertTrue(FireOverlay.hidden(100));
        assertFalse(FireOverlay.hidden(99));
    }

    @Test
    void outOfRangeValuesAreClamped() {
        assertEquals(0f, FireOverlay.drop(-20));
        assertEquals(FireOverlay.FULL_DROP, FireOverlay.drop(250), 1e-6);
    }
}
