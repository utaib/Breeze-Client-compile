package dev.breeze.cosmetics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CapeListsTest {

    @Test
    void ownedIdsAreTheCatalogueCapesOnly() {
        // GET /capes/:uuid as the live API answered it on 2026-10-03.
        String body = "[\"a8ad92c4-a7ad-4fa8-b2db-f04d453a8291\",\"42f373f0-61c6-4d32-a151-e707f64bd5c8\","
                + "\"c44db2ee-e762-46fe-aefb-26f781dfc3a9\",\"testcape\",\"onemorecape\",\"skinmc-custom-cape\"]";
        assertEquals(List.of("a8ad92c4-a7ad-4fa8-b2db-f04d453a8291", "42f373f0-61c6-4d32-a151-e707f64bd5c8",
                "c44db2ee-e762-46fe-aefb-26f781dfc3a9"), CapeLists.ownedIds(body));
    }

    @Test
    void aPlayerWithOnlyTheTestImagesOwnsNothing() {
        assertEquals(List.of(), CapeLists.ownedIds("[\"testcape\",\"onemorecape\",\"skinmc-custom-cape\"]"));
    }

    @Test
    void ownedIdsAreListedOnceAndInLowerCase() {
        assertEquals(List.of("42f373f0-61c6-4d32-a151-e707f64bd5c8"),
                CapeLists.ownedIds("[\"42F373F0-61C6-4D32-A151-E707F64BD5C8\",\"42f373f0-61c6-4d32-a151-e707f64bd5c8\"]"));
    }

    @Test
    void unreadableAnswersGiveNothing() {
        assertEquals(List.of(), CapeLists.ownedIds("not json"));
        assertEquals(List.of(), CapeLists.ownedIds("{\"error\":\"x\"}"));
        assertEquals(List.of(), CapeLists.catalogue("[]"));
        assertEquals(List.of(), CapeLists.catalogue("<html>"));
    }

    @Test
    void theCatalogueCarriesNamesImagesAndFrames() {
        String body = "{\"success\":true,\"capes\":["
                + "{\"id\":\"42f373f0-61c6-4d32-a151-e707f64bd5c8\",\"name\":\"Qwerky Doodle\",\"rarity\":\"rare\","
                + "\"image_url\":\"http://api.breezeclient.net/assets/capes/marketplace/q.png\",\"is_animated\":false,"
                + "\"animation_fps\":null,\"animation_frames\":null},"
                + "{\"id\":\"e090083f-a282-4505-8002-9684dcf5f633\",\"name\":\"Kingsflame Cape\",\"rarity\":\"legendary\","
                + "\"image_url\":\"http://api.breezeclient.net/assets/capes/marketplace/k/frame_000.png\",\"is_animated\":true,"
                + "\"animation_fps\":20,\"animation_frames\":[\"http://a/1.png\",\"http://a/2.png\"]},"
                + "{\"id\":\"no-image\",\"name\":\"Broken\"}]}";
        List<CapeLists.CatalogueCape> capes = CapeLists.catalogue(body);
        assertEquals(2, capes.size(), "a cape without an image is left out");
        assertEquals("Qwerky Doodle", capes.get(0).name);
        assertFalse(capes.get(0).animated);
        assertEquals(12, capes.get(0).fps);
        assertEquals("Kingsflame Cape", capes.get(1).name);
        assertTrue(capes.get(1).animated);
        assertEquals(20, capes.get(1).fps);
        assertEquals(2, capes.get(1).frames.size());
    }

    @Test
    void anAnimatedFlagWithOneFrameIsAStillCape() {
        String body = "{\"data\":{\"capes\":[{\"id\":\"x\",\"image_url\":\"http://a/x.png\",\"is_animated\":true,"
                + "\"animation_frames\":[\"http://a/x.png\"]}]}}";
        List<CapeLists.CatalogueCape> capes = CapeLists.catalogue(body);
        assertEquals(1, capes.size());
        assertFalse(capes.get(0).animated);
        assertEquals("Cape", capes.get(0).name);
    }
}
