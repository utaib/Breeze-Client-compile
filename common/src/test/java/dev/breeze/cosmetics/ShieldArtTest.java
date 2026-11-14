package dev.breeze.cosmetics;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class ShieldArtTest {

    private static final int WOOD = 0xFF6B4A2B;
    private static final int RED = 0xFFFF0000;
    private static final int BLUE = 0xFF0000FF;

    private static int[] base() {
        int[] b = new int[64 * 64];
        Arrays.fill(b, WOOD);
        return b;
    }

    private static int at(int[] tex, int scale, int x, int y) {
        return tex[y * 64 * scale + x];
    }

    @Test
    void scaleGivesThePictureItsDetailUpToEight() {
        assertEquals(1, ShieldArt.scaleFor(12, 22));
        assertEquals(2, ShieldArt.scaleFor(24, 10));
        assertEquals(8, ShieldArt.scaleFor(96, 176));
        assertEquals(8, ShieldArt.scaleFor(2000, 2000));
    }

    @Test
    void coverFillsOnlyTheFrontFace() {
        int[] red = new int[4 * 4];
        Arrays.fill(red, RED);
        int[] tex = ShieldArt.compose(base(), red, 4, 4, 2, ShieldArt.Fit.COVER);
        assertEquals(128 * 128, tex.length);
        // Face: (1,1) to (13,23) at 64, so (2,2) to (26,46) at scale 2.
        assertEquals(RED, at(tex, 2, 2, 2));
        assertEquals(RED, at(tex, 2, 25, 45));
        assertEquals(WOOD, at(tex, 2, 1, 2), "the rim stays the shield's");
        assertEquals(WOOD, at(tex, 2, 26, 2), "the side stays the shield's");
        assertEquals(WOOD, at(tex, 2, 2, 46));
        assertEquals(WOOD, at(tex, 2, 40, 10), "the back stays the shield's");
    }

    @Test
    void containKeepsTheWholePictureAndCoverCropsIt() {
        // A wide picture: left half red, right half blue.
        int[] wide = new int[20 * 10];
        for (int y = 0; y < 10; y++) for (int x = 0; x < 20; x++) wide[y * 20 + x] = x < 10 ? RED : BLUE;
        int[] contain = ShieldArt.compose(base(), wide, 20, 10, 1, ShieldArt.Fit.CONTAIN);
        // 12 wide, 6 high, centred in the 12x22 face: rows 8 to 13 of the face.
        assertEquals(WOOD, at(contain, 1, 3, 1), "above the picture the shield shows");
        assertEquals(RED, at(contain, 1, 2, 12));
        assertEquals(BLUE, at(contain, 1, 12, 12));
        int[] cover = ShieldArt.compose(base(), wide, 20, 10, 1, ShieldArt.Fit.COVER);
        // 44 wide by 22 high centred: only the middle shows, half red, half blue.
        assertEquals(RED, at(cover, 1, 1, 1));
        assertEquals(BLUE, at(cover, 1, 12, 22));
    }

    @Test
    void seeThroughPixelsShowTheShield() {
        int[] img = {0x00FFFFFF, 0x80FF0000};
        int[] tex = ShieldArt.compose(base(), img, 2, 1, 1, ShieldArt.Fit.CONTAIN);
        int left = at(tex, 1, 1, 12), right = at(tex, 1, 12, 12);
        assertEquals(WOOD, left, "fully clear: the shield's own face");
        assertEquals(0xFF, right >>> 24);
        assertTrue(((right >> 16) & 0xFF) > 0xB0 && ((right >> 16) & 0xFF) < 0xC0, "half red over wood");
    }

    @Test
    void pictureNamesStayInsideTheFolder() {
        assertEquals("", ShieldArt.cleanPictureName("  "));
        assertEquals("dragon.png", ShieldArt.cleanPictureName(" dragon.png "));
        assertEquals("My Crest.JPEG", ShieldArt.cleanPictureName("My Crest.JPEG"));
        for (String bad : new String[] {"../secret.png", "a/b.png", "a\\b.png", "C:x.png", ".hidden.png", "picture.gif", "x\npng.png"}) {
            assertThrows(IllegalArgumentException.class, () -> ShieldArt.cleanPictureName(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> ShieldArt.cleanPictureName("a".repeat(118) + ".png"));
    }

    @Test
    void refusesWrongSizes() {
        assertThrows(IllegalArgumentException.class, () -> ShieldArt.compose(new int[10], new int[1], 1, 1, 1, ShieldArt.Fit.COVER));
        assertThrows(IllegalArgumentException.class, () -> ShieldArt.compose(base(), new int[3], 2, 2, 1, ShieldArt.Fit.COVER));
        assertThrows(IllegalArgumentException.class, () -> ShieldArt.compose(base(), new int[1], 1, 1, 9, ShieldArt.Fit.COVER));
    }
}
