package dev.breeze.names;

import com.google.gson.JsonObject;
import dev.breeze.settings.Setting;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LocalNameTest {

    private static String refusal(String raw) {
        return assertThrows(IllegalArgumentException.class, () -> LocalName.clean(raw)).getMessage();
    }

    @Test
    void cleanKeepsOrdinaryNamesAndTidiesSpaces() {
        assertEquals("UthabYT", LocalName.clean("UthabYT"));
        assertEquals("Uthab YT [Live]", LocalName.clean("  Uthab \t YT   [Live] "));
        assertEquals("Ünïcødé ★", LocalName.clean("Ünïcødé ★"));
        assertEquals("x".repeat(LocalName.MAX), LocalName.clean("x".repeat(LocalName.MAX)));
    }

    @Test
    void cleanRefusesWhatCouldBreakChatOrPassForSomethingElse() {
        assertEquals("Type a name.", refusal(""));
        assertEquals("Type a name.", refusal("   "));
        assertEquals("Type a name.", refusal(null));
        assertTrue(refusal("x".repeat(LocalName.MAX + 1)).contains(String.valueOf(LocalName.MAX)));
        assertTrue(refusal("§cRed").contains("Formatting"));
        // Control and invisible characters: a bell, a right-to-left override
        // (would reverse the line around it), a zero-width space.
        assertNotNull(refusal("a\u0007b"));
        assertNotNull(refusal("a‮b"));
        assertNotNull(refusal("a​b"));
        // Breeze's tag icons are private-use glyphs.
        assertTrue(refusal(" Uthab").contains("Breeze"));
        // Outside the font: an emoji.
        assertNotNull(refusal("Uthab 😀"));
    }

    @Test
    void swapReplacesWholeNamesOnly() {
        assertEquals("UthabYT", LocalName.swap("Uthab", "Uthab", "UthabYT"));
        assertEquals("[Owner] UthabYT: hi", LocalName.swap("[Owner] Uthab: hi", "Uthab", "UthabYT"));
        assertEquals("<UthabYT>", LocalName.swap("<Uthab>", "Uthab", "UthabYT"));
        assertEquals("A and A", LocalName.swap("Uthab and Uthab", "Uthab", "A"));
        // Part of a longer name is someone else.
        String other = "Uthab_2 xUthab Uthab9";
        assertSame(other, LocalName.swap(other, "Uthab", "UthabYT"));
        // Case is the server's spelling.
        String lower = "uthab";
        assertSame(lower, LocalName.swap(lower, "Uthab", "UthabYT"));
        // A whole match after a partial one.
        assertEquals("Uthab_2 Me", LocalName.swap("Uthab_2 Uthab", "Uthab", "Me"));
    }

    @Test
    void swapLeavesTextAloneWhenItCannotWork() {
        String s = "hello";
        assertSame(s, LocalName.swap(s, "Uthab", "X"));
        assertSame(s, LocalName.swap(s, "", "X"));
        assertSame(s, LocalName.swap(s, null, "X"));
        assertNull(LocalName.swap(null, "Uthab", "X"));
    }

    /** contract/local-name.json, which the menu's preview fixture is checked against too. */
    @Test
    void matchesTheContract() throws Exception {
        java.nio.file.Path file = java.nio.file.Path.of(System.getProperty("user.dir")).resolve("../contract/local-name.json").normalize();
        com.google.gson.JsonObject c = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file)).getAsJsonObject();
        assertEquals(LocalName.MAX, c.get("max").getAsInt());
        for (var e : c.getAsJsonArray("accepted")) {
            var a = e.getAsJsonArray();
            assertEquals(a.get(1).getAsString(), LocalName.clean(a.get(0).getAsString()));
        }
        for (var e : c.getAsJsonArray("refused")) {
            var a = e.getAsJsonArray();
            assertEquals(a.get(1).getAsString(), refusal(a.get(0).getAsString()));
        }
        for (var e : c.getAsJsonArray("swap")) {
            var a = e.getAsJsonArray();
            assertEquals(a.get(3).getAsString(), LocalName.swap(a.get(0).getAsString(), a.get(1).getAsString(), a.get(2).getAsString()));
        }
    }

    @Test
    void textSettingHoldsOnlyWhatItsRuleAllows() {
        Setting.Text t = new Setting.Text("name", "Name", "Name", "You", LocalName.MAX, LocalName::clean);
        t.set("  Uthab  YT ");
        assertEquals("Uthab YT", t.value);
        assertThrows(IllegalArgumentException.class, () -> t.set("§kX"));
        assertEquals("Uthab YT", t.value, "a refused value keeps the old one");

        JsonObject saved = new JsonObject();
        t.write(saved);
        Setting.Text back = new Setting.Text("name", "Name", "Name", "You", LocalName.MAX, LocalName::clean);
        back.read(saved);
        assertEquals("Uthab YT", back.value);

        // A config edited by hand with a refused value falls back to the default.
        JsonObject bad = new JsonObject();
        bad.addProperty("name", "a‮b");
        back.read(bad);
        assertEquals("You", back.value);
        back.read(new JsonObject());
        assertEquals("You", back.value);
    }
}
