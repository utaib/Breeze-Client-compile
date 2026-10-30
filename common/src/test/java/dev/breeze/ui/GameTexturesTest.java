package dev.breeze.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GameTexturesTest {

    @Test
    void everyNameHasAPlaceInMinecraftsTextures() {
        for (String name : GameTextures.names()) {
            List<ModuleIcons.Source> s = GameTextures.sources(name);
            assertFalse(s.isEmpty(), name);
            for (ModuleIcons.Source src : s) {
                assertEquals("minecraft", src.namespace(), name);
                assertTrue(src.path().startsWith("textures/") && src.path().endsWith(".png"), src.path());
            }
        }
    }

    @Test
    void emptyArmourSlotsLookForTheSpriteThenTheOlderItemTexture() {
        List<ModuleIcons.Source> s = GameTextures.sources(GameTextures.EMPTY_HELMET);
        assertEquals("textures/gui/sprites/container/slot/helmet.png", s.get(0).path());
        assertEquals("textures/item/empty_armor_slot_helmet.png", s.get(1).path());
    }

    @Test
    void theSlotIsOneSquareOfTheInventoryPicture() {
        ModuleIcons.Source slot = GameTextures.sources(GameTextures.SLOT).get(0);
        assertEquals("textures/gui/container/inventory.png", slot.path());
        ModuleIcons.Crop c = ModuleIcons.crop(slot, 256, 256);
        assertEquals(new ModuleIcons.Crop(7, 83, 18, 256, 256), c);
        // A resource pack at twice the size: the same square, twice as big.
        assertEquals(new ModuleIcons.Crop(14, 166, 36, 512, 512), ModuleIcons.crop(slot, 512, 512));
    }

    @Test
    void theArmourIconIsASpriteOrASquareOfTheOldIconSheet() {
        List<ModuleIcons.Source> s = GameTextures.sources(GameTextures.ARMOR_ICON);
        assertEquals("textures/gui/sprites/hud/armor_full.png", s.get(0).path());
        assertTrue(s.get(1).cropped());
        assertEquals(34, s.get(1).cropX());
    }

    @Test
    void effectIconsComeFromTheEffectsTranslationKey() {
        List<ModuleIcons.Source> s = GameTextures.effect("effect.minecraft.speed");
        assertEquals("textures/mob_effect/speed.png", s.get(0).path());
        assertTrue(GameTextures.effect("effect.othermod.zoom").isEmpty());
        assertTrue(GameTextures.effect("item.minecraft.stone").isEmpty());
        assertTrue(GameTextures.effect("effect.minecraft.").isEmpty());
        assertTrue(GameTextures.effect(null).isEmpty());
        assertTrue(GameTextures.effect("effect.minecraft.../x").isEmpty());
    }

    @Test
    void unknownNamesHaveNoPlaces() {
        assertTrue(GameTextures.sources("nothing").isEmpty());
    }
}
