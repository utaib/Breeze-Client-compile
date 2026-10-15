package dev.breeze.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModuleIconsTest {

    @Test
    void anEntryIsATextureInMinecraftsAssets() {
        ModuleIcons.Source s = ModuleIcons.parse("item/clock_00");
        assertEquals("minecraft", s.namespace());
        assertEquals("textures/item/clock_00.png", s.path());
        assertFalse(s.cropped());
    }

    @Test
    void aSquareOfASharedSheetIsGivenAgainstItsVanillaSize() {
        ModuleIcons.Source heart = ModuleIcons.parse("gui/icons#52,0,9");
        assertEquals("textures/gui/icons.png", heart.path());
        assertTrue(heart.cropped());
        assertEquals(new ModuleIcons.Crop(52, 0, 9, 256, 256), ModuleIcons.crop(heart, 256, 256));
        // A resource pack's sheet at twice the size: the same picture, twice as large.
        assertEquals(new ModuleIcons.Crop(104, 0, 18, 512, 512), ModuleIcons.crop(heart, 512, 512));
        // A sheet too small to hold it is not used.
        assertNull(ModuleIcons.crop(heart, 32, 32));
    }

    @Test
    void anAnimationStripShowsItsFirstFrame() {
        ModuleIcons.Source s = ModuleIcons.parse("block/command_block_front");
        assertEquals(new ModuleIcons.Crop(0, 0, 16, 16, 64), ModuleIcons.crop(s, 16, 64));
        assertEquals(new ModuleIcons.Crop(0, 0, 32, 32, 32), ModuleIcons.crop(s, 32, 32));
        assertNull(ModuleIcons.crop(s, 0, 16));
    }

    @Test
    void badEntriesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> ModuleIcons.parse("../../secret"));
        assertThrows(IllegalArgumentException.class, () -> ModuleIcons.parse("/item/clock"));
        assertThrows(IllegalArgumentException.class, () -> ModuleIcons.parse("item/Clock"));
        assertThrows(IllegalArgumentException.class, () -> ModuleIcons.parse("gui/icons#250,0,9"));
        assertThrows(IllegalArgumentException.class, () -> ModuleIcons.parse("gui/icons#1,2"));
    }

    @Test
    void everyModuleHasAPictureAndTheyAreNotAllTheSame() {
        assertEquals(81, ModuleIcons.modules().size());
        long distinctFirst = ModuleIcons.modules().stream()
                .map(m -> ModuleIcons.sources(m).get(0).path())
                .distinct().count();
        assertEquals(81, distinctFirst, "two modules start from the same picture");
        assertEquals(List.of(), ModuleIcons.sources("No Such Module"));
    }

    @Test
    void theOwnersPicksAreKept() {
        assertEquals("textures/item/clock_00.png", ModuleIcons.sources("Stopwatch").get(0).path());
        assertEquals("textures/block/redstone_torch.png", ModuleIcons.sources("FPS").get(0).path());
        assertEquals("textures/gui/sprites/hud/food_full.png", ModuleIcons.sources("Saturation").get(0).path());
        assertEquals("textures/gui/sprites/hud/heart/full.png", ModuleIcons.sources("Hearts").get(0).path());
    }
}
