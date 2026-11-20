package dev.breeze.hud;

import dev.breeze.hud.HotbarArt.Blit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HotbarArtTest {

    private static final HotbarArt.Source SPRITE = HotbarArt.SOURCES.get(0);
    private static final HotbarArt.Source WIDGETS = HotbarArt.SOURCES.get(1);

    @Test
    void theSpriteComesFirstAndTheOldSheetAfter() {
        assertEquals("textures/gui/sprites/hud/hotbar.png", SPRITE.path());
        assertEquals("textures/gui/widgets.png", WIDGETS.path());
    }

    @Test
    void aResourcePacksLargerHotbarIsAWholeMultiple() {
        assertEquals(1, HotbarArt.scale(SPRITE, 182, 22));
        assertEquals(2, HotbarArt.scale(SPRITE, 364, 44));
        assertEquals(0, HotbarArt.scale(SPRITE, 182, 44), "an animated strip of frames is not a hotbar");
        assertEquals(0, HotbarArt.scale(SPRITE, 200, 22));
        assertEquals(1, HotbarArt.scale(WIDGETS, 256, 256));
        assertEquals(4, HotbarArt.scale(WIDGETS, 1024, 1024));
        assertEquals(0, HotbarArt.scale(WIDGETS, 256, 128));
        assertEquals(0, HotbarArt.scale(SPRITE, 0, 0));
    }

    @Test
    void aRowIsTheHotbarCutAfterTheLastSlotThenItsOutline() {
        assertEquals(List.of(new Blit(0, 0, 81, 22, 0, 0), new Blit(81, 0, 1, 22, 181, 0)),
                HotbarArt.strip(4, false));
        // Nine slots is the whole hotbar.
        assertEquals(List.of(new Blit(0, 0, 181, 22, 0, 0), new Blit(181, 0, 1, 22, 181, 0)),
                HotbarArt.strip(9, false));
        assertEquals(HotbarArt.WIDTH, HotbarArt.length(9));
    }

    @Test
    void aColumnRepeatsTheSlotRowsBetweenTheOutlineRows() {
        List<Blit> down = HotbarArt.strip(2, true);
        assertEquals(8, down.size());
        assertTrue(down.contains(new Blit(0, 0, 21, 1, 0, 0)), "top outline");
        assertTrue(down.contains(new Blit(0, 41, 21, 1, 0, 21)), "bottom outline");
        assertTrue(down.contains(new Blit(0, 1, 21, 20, 0, 1)), "first slot");
        assertTrue(down.contains(new Blit(0, 21, 21, 20, 0, 1)), "second slot, the same rows again");
        assertTrue(down.contains(new Blit(21, 21, 1, 20, 181, 1)), "right outline beside it");
        // Every pixel of the 22 by 42 column is covered exactly once.
        int[][] cover = new int[42][22];
        for (Blit b : down) {
            for (int y = b.y(); y < b.y() + b.h(); y++) {
                for (int x = b.x(); x < b.x() + b.w(); x++) cover[y][x]++;
            }
        }
        for (int[] row : cover) for (int c : row) assertEquals(1, c);
        assertEquals(42, HotbarArt.length(2));
    }

    @Test
    void itemsSitWhereTheHotbarDrawsItsOwn() {
        assertEquals(3, HotbarArt.itemOffset(0));
        assertEquals(163, HotbarArt.itemOffset(8));
    }

    @Test
    void outOfRangeStripsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> HotbarArt.strip(0, false));
        assertThrows(IllegalArgumentException.class, () -> HotbarArt.strip(10, true));
    }
}
