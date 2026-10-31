package dev.breeze.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HitboxGeometryTest {

    @Test
    void aPlayerSizedBoxWithItsEyesAndLook() {
        // A player: 0.6 wide, 1.8 tall, eyes at 1.62, looking north (-z).
        HitboxGeometry.Lines l = HitboxGeometry.of(-0.3, 0, -0.3, 0.3, 1.8, 0.3, 1.62, 0, 0, -1, true, true);
        assertEquals(12 * 6, l.box().length);
        assertEquals(4 * 6, l.eye().length);
        for (int i = 1; i < l.eye().length; i += 3) assertEquals(1.62f, l.eye()[i], 1e-6f, "the ring is at eye height");
        // From the middle of the box at eye height, 2 blocks along the look.
        assertArrayEquals(new float[] {0, 1.62f, 0, 0, 1.62f, -2}, l.look(), 1e-6f);
    }

    @Test
    void theLookLineIsTwoBlocksWhateverTheVectorsLength() {
        HitboxGeometry.Lines l = HitboxGeometry.of(0, 0, 0, 1, 1, 1, 0.5, 3, 0, 4, true, true);
        float dx = l.look()[3] - l.look()[0], dz = l.look()[5] - l.look()[2];
        assertEquals(2.0, Math.sqrt(dx * dx + dz * dz), 1e-5);
    }

    @Test
    void partsSwitchedOffOrMeaninglessAreLeftOut() {
        assertNull(HitboxGeometry.of(0, 0, 0, 1, 1, 1, 0.5, 0, 0, 1, false, false).eye());
        assertNull(HitboxGeometry.of(0, 0, 0, 1, 1, 1, 0.5, 0, 0, 1, false, false).look());
        // Eyes outside the box (an item lying flat): no ring.
        assertNull(HitboxGeometry.of(0, 0, 0, 1, 0.25, 1, 0.5, 0, 0, 1, true, true).eye());
        // No direction: no look line.
        assertNull(HitboxGeometry.of(0, 0, 0, 1, 1, 1, 0.5, 0, 0, 0, true, true).look());
    }
}
