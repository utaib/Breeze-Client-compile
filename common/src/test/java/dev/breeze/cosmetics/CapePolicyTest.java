package dev.breeze.cosmetics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CapePolicyTest {

    /** A player as one renderer sees them. Records which lookups were asked. */
    static final class Player implements CapePolicy.Lookup<String> {
        boolean loaded;
        boolean equipped;
        String equippedTexture;
        boolean failed;
        String legacySelf;
        String local;
        boolean breezeUser;
        String legacyPlayer;
        final List<String> asked = new ArrayList<>();

        public boolean stateLoaded() { return loaded; }
        public boolean capeEquipped() { return equipped; }
        public String equippedTexture() { asked.add("equipped"); return equippedTexture; }
        public boolean equippedFailed() { return failed; }
        public String legacySelfTexture() { asked.add("legacySelf"); return legacySelf; }
        public String localTexture() { asked.add("local"); return local; }
        public boolean breezeUser() { return breezeUser; }
        public String legacyPlayerTexture() { asked.add("legacyPlayer"); return legacyPlayer; }
    }

    private static Player equipped(String texture) {
        Player p = new Player();
        p.loaded = true;
        p.equipped = true;
        p.equippedTexture = texture;
        p.legacySelf = "legacy-self";
        p.local = "local";
        p.breezeUser = true;
        p.legacyPlayer = "legacy-player";
        return p;
    }

    @Test
    void yourEquippedCapeShowsWithTheModuleOff() {
        // The spec's activation bug: equipping in the Wardrobe must be enough.
        assertEquals("wind", CapePolicy.pick(true, false, equipped("wind")));
    }

    @Test
    void yourEquippedCapeWinsOverTheLocalCape() {
        assertEquals("wind", CapePolicy.pick(true, true, equipped("wind")));
    }

    @Test
    void anotherPlayersEquippedCapeShows() {
        assertEquals("wind", CapePolicy.pick(false, false, equipped("wind")));
    }

    @Test
    void unequippedMeansNoCapeEvenWithStaleLegacyCaches() {
        // Unequip: the state says no cape, and the older routes still hold the
        // old one. For you and for everyone watching you, nothing is drawn.
        Player p = equipped("wind");
        p.equipped = false;
        assertNull(CapePolicy.pick(true, false, p));
        assertNull(CapePolicy.pick(false, false, p));
        assertFalse(p.asked.contains("legacySelf"));
        assertFalse(p.asked.contains("legacyPlayer"));
    }

    @Test
    void unequippedWithTheModuleOnShowsYourLocalCapeToYouOnly() {
        Player p = equipped("wind");
        p.equipped = false;
        assertEquals("local", CapePolicy.pick(true, true, p));
        assertNull(CapePolicy.pick(false, true, p));
    }

    @Test
    void whileTheEquippedCapeDownloadsNothingElseFlashes() {
        Player p = equipped(null);
        assertNull(CapePolicy.pick(true, true, p));
        assertNull(CapePolicy.pick(false, false, p));
        assertEquals(List.of("equipped", "equipped"), p.asked);
    }

    @Test
    void aFailedImageFallsBackToTheOlderRoutes() {
        Player p = equipped(null);
        p.failed = true;
        assertEquals("legacy-self", CapePolicy.pick(true, false, p));
        assertEquals("legacy-player", CapePolicy.pick(false, false, p));
    }

    @Test
    void beforeTheStateLoadsTheOlderRoutesAreUsed() {
        Player p = equipped("wind");
        p.loaded = false;
        assertEquals("legacy-self", CapePolicy.pick(true, false, p));
        assertEquals("legacy-player", CapePolicy.pick(false, false, p));
        assertFalse(p.asked.contains("equipped"));
    }

    @Test
    void beforeTheStateLoadsYourLocalCapeIsTheLastResort() {
        Player p = new Player();
        p.local = "local";
        assertNull(CapePolicy.pick(true, false, p));
        assertEquals("local", CapePolicy.pick(true, true, p));
    }

    @Test
    void playersNotKnownToUseBreezeAreNotAskedAbout() {
        Player p = new Player();
        p.legacyPlayer = "legacy-player";
        assertNull(CapePolicy.pick(false, true, p));
        assertTrue(p.asked.isEmpty());
    }

    @Test
    void theLocalCapeIsNeverShownOnAnotherPlayer() {
        Player p = new Player();
        p.local = "local";
        p.breezeUser = true;
        assertNull(CapePolicy.pick(false, true, p));
        assertFalse(p.asked.contains("local"));
    }
}
