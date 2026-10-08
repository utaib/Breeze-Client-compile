package dev.breeze.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TagArtTest {

    @Test
    void theApisRoleColoursGetTheirWindCharge() {
        // The tags table's colours, read 2026-10-08.
        assertEquals(TagArt.RED, TagArt.of(0xFF5555));      // owner
        assertEquals(TagArt.PURPLE, TagArt.of(0xA56EFF));   // developer
        assertEquals(TagArt.PURPLE, TagArt.of(0x800080));   // admin
        assertEquals(TagArt.YELLOW, TagArt.of(0xFFD23F));   // creator
        assertEquals(TagArt.YELLOW, TagArt.of(0xFFD700));   // donator
        assertEquals(TagArt.BLUE, TagArt.of(0x55C8FF));     // breeze
        assertEquals(TagArt.BLUE, TagArt.of(0x55FFFF));     // the old default
    }

    @Test
    void greenPinkGreysAndAlpha() {
        assertEquals(TagArt.GREEN, TagArt.of(0x55FF55));
        assertEquals(TagArt.GREEN, TagArt.of(0x2E8B57));
        assertEquals(TagArt.PINK, TagArt.of(0xFF69B4));
        assertEquals(TagArt.PLAIN, TagArt.of(0xFFFFFF));
        assertEquals(TagArt.PLAIN, TagArt.of(0x808080));
        assertEquals(TagArt.PLAIN, TagArt.of(0x000000));
        assertEquals(TagArt.RED, TagArt.of(0xFFFF5555));    // alpha ignored
    }

    @Test
    void oneFontCharacterEachInTheSheetsOrder() {
        // assets/minecraft/font/default.json lists U+EB2E to U+EB34 for the
        // sheet's seven cells: plain, blue, green, pink, purple, red, yellow.
        assertEquals("", TagArt.PLAIN.glyph());
        assertEquals("", TagArt.GREEN.glyph());
        assertEquals("", TagArt.YELLOW.glyph());
        assertEquals(7, TagArt.values().length);
    }

    /** The font files the base version ships, read from the source tree. */
    private static Path assets() {
        return Path.of(System.getProperty("user.dir"))
                .resolve("../versions/1.20.1/src/main/resources/assets").normalize();
    }

    @Test
    void theFontListsEveryPictureOnceOverTheWholeSheet() throws Exception {
        for (String font : new String[] {"default", "uniform"}) {
            JsonObject provider = JsonParser.parseString(Files.readString(assets().resolve("minecraft/font/" + font + ".json")))
                    .getAsJsonObject().getAsJsonArray("providers").get(0).getAsJsonObject();
            assertEquals("breeze:font/tags.png", provider.get("file").getAsString(), font);
            JsonArray rows = provider.getAsJsonArray("chars");
            assertEquals(1, rows.size(), font);
            StringBuilder want = new StringBuilder();
            for (TagArt art : TagArt.values()) want.append(art.glyph());
            assertEquals(want.toString(), rows.get(0).getAsString(), font);
            // Text metrics: 8 high with 7 above the line, like Minecraft's letters.
            assertEquals(8, provider.get("height").getAsInt(), font);
            assertEquals(7, provider.get("ascent").getAsInt(), font);
        }
        BufferedImage sheet = ImageIO.read(assets().resolve("breeze/textures/font/tags.png").toFile());
        assertEquals(12 * TagArt.values().length, sheet.getWidth());
        assertEquals(10, sheet.getHeight());
        for (int cell = 0; cell < TagArt.values().length; cell++) {
            // Every picture reaches its cell's last column, so each tag is
            // equally wide in game (the self-test checks 11: 12 x 0.8, plus 1).
            boolean edge = false;
            for (int y = 0; y < 10; y++) edge |= (sheet.getRGB(cell * 12 + 11, y) >>> 24) != 0;
            assertTrue(edge, "cell " + cell);
        }
    }
}
