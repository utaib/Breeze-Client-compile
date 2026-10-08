package dev.breeze.cosmetics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OwnedCosmeticsTest {

    // The shape GET /cosmetics/owned/:uuid answers with (sendOwnedCosmetics in the API).
    private static final String ANSWER = "{\"success\":true,"
            + "\"owned\":["
            + "{\"cosmetic_id\":\"pet-1\",\"acquired_at\":\"2026-09-01\",\"cosmetic\":{\"id\":\"pet-1\",\"slot\":\"pet\",\"name\":\"Wind Sprite\",\"model_url\":\"https://api.breezeclient.net/a.glb\"}},"
            + "{\"cosmetic_id\":\"hat-2\",\"cosmetic\":{\"id\":\"hat-2\",\"slot\":\"hat\",\"name\":\"zeta Cap\"}},"
            + "{\"cosmetic_id\":\"hat-1\",\"cosmetic\":{\"id\":\"hat-1\",\"slot\":\"hat\",\"name\":\"Alpha Hat\"}},"
            + "{\"cosmetic_id\":\"gone\",\"cosmetic\":null},"
            + "{\"cosmetic_id\":\"odd\",\"cosmetic\":{\"id\":\"odd\",\"slot\":\"tail\",\"name\":\"Unknown slot\"}},"
            + "{\"cosmetic_id\":\"noname\",\"cosmetic\":{\"id\":\"noname\",\"slot\":\"trail\",\"name\":\"\"}}"
            + "],"
            + "\"equipped\":{\"hat\":\"hat-2\",\"pet\":\"pet-1\"},"
            + "\"placements\":{}}";

    @Test
    void readsOwnedCosmeticsInSlotOrderWithWhatIsEquipped() {
        List<OwnedCosmetics.Item> items = OwnedCosmetics.parse(ANSWER);
        assertEquals(List.of("hat-1", "hat-2", "pet-1", "noname"), items.stream().map(i -> i.id).toList());
        assertFalse(items.get(0).equipped);
        assertTrue(items.get(1).equipped, "the equipped hat is the one in equipped.hat");
        assertTrue(items.get(2).equipped);
        assertEquals("Wind Sprite", items.get(2).name);
        assertEquals("noname", items.get(3).name, "a cosmetic with no name shows its id");
    }

    @Test
    void leavesOutRowsItCannotUse() {
        List<OwnedCosmetics.Item> items = OwnedCosmetics.parse(ANSWER);
        assertTrue(items.stream().noneMatch(i -> i.id.equals("gone")), "a deleted cosmetic");
        assertTrue(items.stream().noneMatch(i -> i.id.equals("odd")), "a slot the API does not have");
    }

    @Test
    void aMalformedAnswerIsAnEmptyList() {
        assertTrue(OwnedCosmetics.parse("not json {").isEmpty());
        assertTrue(OwnedCosmetics.parse("[]").isEmpty());
        assertTrue(OwnedCosmetics.parse("{\"owned\":\"x\"}").isEmpty());
        assertTrue(OwnedCosmetics.parse("").isEmpty());
    }

    @Test
    void slots() {
        assertTrue(OwnedCosmetics.validSlot("trail"));
        assertFalse(OwnedCosmetics.validSlot("tail"));
        assertFalse(OwnedCosmetics.validSlot(null));
        assertEquals("Wings", OwnedCosmetics.slotLabel("wings"));
    }
}
