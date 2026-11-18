package dev.breeze.cosmetics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ShieldMeshTest {

    @Test
    void twoBoxesOfSixFaces() {
        assertEquals(12, ShieldMesh.quads().size());
    }

    @Test
    void theFrontFaceUsesThePictureArea() {
        // The plate's north face, turned to face the viewer by the shield's (1, -1, -1).
        ShieldMesh.Quad front = ShieldMesh.quads().get(3);
        assertEquals(1f, front.nz(), "faces +z after the turn");
        float minU = 1, maxU = 0, minV = 1, maxV = 0;
        for (int i = 0; i < 4; i++) {
            float u = front.corners()[i * 5 + 3], v = front.corners()[i * 5 + 4];
            minU = Math.min(minU, u);
            maxU = Math.max(maxU, u);
            minV = Math.min(minV, v);
            maxV = Math.max(maxV, v);
        }
        assertEquals(ShieldArt.FACE_X / 64f, minU, 1e-6);
        assertEquals((ShieldArt.FACE_X + ShieldArt.FACE_W) / 64f, maxU, 1e-6);
        assertEquals(ShieldArt.FACE_Y / 64f, minV, 1e-6);
        assertEquals((ShieldArt.FACE_Y + ShieldArt.FACE_H) / 64f, maxV, 1e-6);
    }

    @Test
    void thePlateIsTwelveByTwentyTwoPixelsInBlocks() {
        float minX = 9, maxX = -9, minY = 9, maxY = -9;
        for (ShieldMesh.Quad q : ShieldMesh.quads().subList(0, 6)) {
            for (int i = 0; i < 4; i++) {
                minX = Math.min(minX, q.corners()[i * 5]);
                maxX = Math.max(maxX, q.corners()[i * 5]);
                minY = Math.min(minY, q.corners()[i * 5 + 1]);
                maxY = Math.max(maxY, q.corners()[i * 5 + 1]);
            }
        }
        assertEquals(12 / 16f, maxX - minX, 1e-6);
        assertEquals(22 / 16f, maxY - minY, 1e-6);
        assertEquals(11 / 16f, maxY, 1e-6, "y flipped: the top of the plate is at +11");
    }

    @Test
    void theTopImageRowIsAtTheTopOfTheShield() {
        ShieldMesh.Quad front = ShieldMesh.quads().get(3);
        List<float[]> corners = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) corners.add(java.util.Arrays.copyOfRange(front.corners(), i * 5, i * 5 + 5));
        for (float[] c : corners) {
            // v at the face's top edge (smallest v) belongs to the highest y.
            if (c[4] < 2 / 64f) assertTrue(c[1] > 0, "top of the picture is up");
            else assertTrue(c[1] < 0);
        }
    }
}
