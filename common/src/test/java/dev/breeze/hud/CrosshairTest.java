package dev.breeze.hud;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

/** The crosshair against contract/crosshair.json, the file the menu preview is tested against too. */
class CrosshairTest {

    static JsonObject contract() throws Exception {
        Path file = Path.of(System.getProperty("user.dir")).resolve("../contract/crosshair.json").normalize();
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }

    static int argb(String hex) {
        return (int) Long.parseLong(hex, 16);
    }

    static Crosshair.Config config(JsonObject o) {
        return new Crosshair.Config(o.get("style").getAsString(), o.get("length").getAsInt(), o.get("thickness").getAsInt(),
                o.get("gap").getAsInt(), argb(o.get("color").getAsString()), o.get("dot").getAsBoolean(),
                o.get("outline").getAsBoolean(), o.get("outlineThickness").getAsInt(), argb(o.get("outlineColor").getAsString()));
    }

    @Test
    void everyExampleHasItsShapeAndItsCode() throws Exception {
        for (JsonElement e : contract().getAsJsonArray("examples")) {
            JsonObject ex = e.getAsJsonObject();
            String name = ex.get("name").getAsString();
            Crosshair.Config c = config(ex.getAsJsonObject("config"));
            List<Crosshair.Rect> want = new ArrayList<>();
            for (JsonElement r : ex.getAsJsonArray("rects")) {
                JsonArray a = r.getAsJsonArray();
                want.add(new Crosshair.Rect(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt(), a.get(3).getAsInt(), argb(a.get(4).getAsString())));
            }
            assertEquals(want, Crosshair.rects(c), name);
            assertEquals(ex.get("code").getAsString(), Crosshair.encode(c), name);
            Crosshair.Decoded back = Crosshair.decode(ex.get("code").getAsString());
            assertTrue(back.ok(), name + ": " + back.error());
            assertEquals(c, back.config(), name);
        }
    }

    @Test
    void refusedCodesSayWhy() throws Exception {
        for (JsonElement e : contract().getAsJsonArray("refused")) {
            JsonObject ex = e.getAsJsonObject();
            Crosshair.Decoded d = Crosshair.decode(ex.get("code").getAsString());
            assertFalse(d.ok(), ex.get("code").getAsString());
            assertEquals(ex.get("why").getAsString(), d.error());
        }
        assertFalse(Crosshair.decode(null).ok());
    }

    @Test
    void aCodeIsReadWhateverItsCaseOfPrefixOrSurroundingSpace() throws Exception {
        String code = contract().getAsJsonArray("examples").get(0).getAsJsonObject().get("code").getAsString();
        assertTrue(Crosshair.decode("  bzc-" + code.substring(4) + "\n").ok());
    }

    @Test
    void aWellFormedCodeWithANumberOutOfRangeIsRefused() {
        byte[] b = Base64.getUrlDecoder().decode(Crosshair.encode(Crosshair.Config.defaults()).substring(4));
        b[2] = 99; // length
        CRC32 crc = new CRC32();
        crc.update(b, 0, 15);
        b[15] = (byte) (crc.getValue() >>> 8);
        b[16] = (byte) crc.getValue();
        Crosshair.Decoded d = Crosshair.decode("BZC-" + Base64.getUrlEncoder().withoutPadding().encodeToString(b));
        assertFalse(d.ok());
        assertEquals("That code has a setting this Breeze cannot show. Ask for a new code.", d.error());
    }

    @Test
    void settingsOutOfRangeAreDrawnAndSharedInRange() {
        Crosshair.Config wild = new Crosshair.Config("Star", 500, 0, -4, 0xFFFFFFFF, true, true, 9, 0xFF000000);
        Crosshair.Config in = wild.clamped();
        assertEquals("Cross", in.style());
        assertEquals(Crosshair.LENGTH_MAX, in.length());
        assertEquals(Crosshair.THICKNESS_MIN, in.thickness());
        assertEquals(Crosshair.GAP_MIN, in.gap());
        assertEquals(Crosshair.OUTLINE_MAX, in.outlineThickness());
        assertEquals(in, Crosshair.decode(Crosshair.encode(wild)).config());
        assertEquals(Crosshair.rects(in), Crosshair.rects(wild));
    }

    @Test
    void everyStyleAndSizeRoundTrips() {
        for (String style : Crosshair.STYLES)
            for (int t = Crosshair.THICKNESS_MIN; t <= Crosshair.THICKNESS_MAX; t++)
                for (int g = Crosshair.GAP_MIN; g <= Crosshair.GAP_MAX; g += 3) {
                    Crosshair.Config c = new Crosshair.Config(style, 7, t, g, 0x12345678, t % 2 == 0, g % 2 == 0, 2, 0x9ABCDEF0);
                    assertEquals(c, Crosshair.decode(Crosshair.encode(c)).config(), style + " " + t + " " + g);
                    // Odd thicknesses are centred on the centre pixel, even ones one pixel up and left of it.
                    for (Crosshair.Rect r : Crosshair.rects(c)) assertTrue(r.x1() > r.x0() && r.y1() > r.y0(), "empty rectangle");
                }
    }
}
