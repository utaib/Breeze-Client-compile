package dev.breeze.settings;

import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.breeze.bridge.BridgeError;
import dev.breeze.bridge.BridgeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class UiSettingsTest {

    @TempDir
    Path dir;

    @Test
    void defaultsMatchWhatTheInterfaceShips() {
        UiSettings s = new UiSettings();
        assertEquals("{\"theme\":\"glass\",\"accent\":\"blue\",\"uiScale\":0,\"animations\":true,"
                + "\"reduceTransparency\":false,\"replaceTitleScreen\":true,\"backdropDim\":55}", s.toJson().toString());
    }

    @Test
    void validWritesApply() {
        UiSettings s = new UiSettings();
        s.set("theme", new JsonPrimitive("abyss"));
        s.set("accent", new JsonPrimitive("pink"));
        s.set("uiScale", new JsonPrimitive(125));
        s.set("uiScale", new JsonPrimitive(0));
        s.set("animations", new JsonPrimitive(false));
        s.set("backdropDim", new JsonPrimitive(90));
        assertEquals("abyss", s.theme);
        assertEquals("pink", s.accent);
        assertEquals(0, s.uiScale);
        assertFalse(s.animations);
        assertEquals(90, s.backdropDim);
        assertEquals(0xF28BC0, s.accentRgb());
    }

    @Test
    void invalidWritesAreRefusedNotClamped() {
        UiSettings s = new UiSettings();
        String[][] bad = {
                {"theme", "\"neon\""}, {"theme", "3"}, {"accent", "\"purple\""},
                {"uiScale", "74"}, {"uiScale", "151"}, {"uiScale", "101"}, {"uiScale", "100.5"}, {"uiScale", "\"100\""},
                {"animations", "\"false\""}, {"backdropDim", "-1"}, {"backdropDim", "91"}, {"nope", "true"},
        };
        for (String[] b : bad) {
            BridgeException e = assertThrows(BridgeException.class, () -> s.set(b[0], JsonParser.parseString(b[1])), b[0] + "=" + b[1]);
            assertEquals(BridgeError.INVALID_PARAMS, e.error());
        }
        assertEquals(new UiSettings().toJson(), s.toJson(), "nothing changed");
    }

    @Test
    void savesAtomicallyAndLoadsBack() throws Exception {
        Path file = dir.resolve("config/breeze-ui.json");
        UiSettings s = new UiSettings();
        s.theme = "forest";
        s.uiScale = 110;
        s.save(file);
        assertFalse(Files.exists(file.resolveSibling("breeze-ui.json.tmp")));
        assertEquals(s.toJson(), UiSettings.load(file).toJson());
    }

    @Test
    void aDamagedFileKeepsEveryValueItCan() throws Exception {
        Path file = dir.resolve("breeze-ui.json");
        Files.writeString(file, "{\"theme\":\"white\",\"accent\":42,\"uiScale\":9999,\"animations\":false,\"extra\":{}}");
        UiSettings s = UiSettings.load(file);
        assertEquals("white", s.theme);
        assertEquals("blue", s.accent);
        assertEquals(0, s.uiScale);
        assertFalse(s.animations);
    }

    @Test
    void garbageOrMissingFilesGiveDefaults() throws Exception {
        assertEquals(new UiSettings().toJson(), UiSettings.load(dir.resolve("missing.json")).toJson());
        Path file = dir.resolve("broken.json");
        Files.writeString(file, "{not json");
        assertEquals(new UiSettings().toJson(), UiSettings.load(file).toJson());
        Files.writeString(file, "[1,2]");
        assertEquals(new UiSettings().toJson(), UiSettings.load(file).toJson());
    }
}
