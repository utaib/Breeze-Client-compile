package dev.breeze.cosmetics;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class TextureNamesTest {

    private static void assertValid(String s) {
        assertTrue(s.matches("[a-z0-9_.-]+"), () -> "not a valid resource path segment: " + s);
    }

    @Test
    void validNamesStayAsTheyAre() {
        assertEquals("wind_charge", TextureNames.segment("wind_charge"));
        assertEquals("cape-2.v1", TextureNames.segment("cape-2.v1"));
    }

    @Test
    void spacesAndCapitalsBecomeValid() {
        String s = TextureNames.segment("Wind Charge");
        assertValid(s);
        assertTrue(s.startsWith("wind_charge_"));
    }

    @Test
    void namesThatCleanUpAlikeStayDistinct() {
        assertNotEquals(TextureNames.segment("Wind Charge"), TextureNames.segment("wind charge"));
        assertNotEquals(TextureNames.segment("wind charge"), TextureNames.segment("wind_charge"));
    }

    @Test
    void theSameNameAlwaysGivesTheSameSegment() {
        assertEquals(TextureNames.segment("Wind Charge"), TextureNames.segment("Wind Charge"));
    }

    @Test
    void turkishLocaleDoesNotProduceADotlessI() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            String s = TextureNames.segment("TITAN");
            assertValid(s);
            assertTrue(s.startsWith("titan_"));
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    void emptyUnicodeAndLongNames() {
        assertEquals("_", TextureNames.segment(null));
        assertEquals("_", TextureNames.segment(""));
        assertValid(TextureNames.segment("风之披风 ✨"));
        assertValid(TextureNames.segment("../../evil/path"));
        String longName = "x".repeat(500);
        assertTrue(TextureNames.segment(longName).length() <= 64 + 9);
    }
}
