package dev.breeze.integrations;

import com.google.gson.JsonObject;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IntegrationsTest {

    /** A mod with a settings screen, as Zoomify or Sodium register one. */
    static final class ZoomifyEntry implements ModMenuApi {
        @Override
        public ConfigScreenFactory<?> getModConfigScreenFactory() {
            return parent -> new FakeScreen("zoomify:settings", parent);
        }
    }

    /** A mod that registers the entrypoint but offers no screen (Mod Menu's default). */
    static final class BadgeOnlyEntry implements ModMenuApi {}

    /** Mod Menu's own entrypoint provides Minecraft's options for "minecraft". */
    static final class ProvidesEntry implements ModMenuApi {
        @Override
        public Map<String, ConfigScreenFactory<?>> getProvidedConfigScreenFactories() {
            return Map.of("minecraft", parent -> new FakeScreen("options", parent));
        }
    }

    @Test
    void modMenusOwnScreensOpenThroughItsPublishedApi() throws Exception {
        ModMenuApiCalls calls = ModMenuApiCalls.find(getClass().getClassLoader(), FakeScreen.class);
        FakeScreen breeze = new FakeScreen("breeze", null);

        FakeScreen mods = (FakeScreen) calls.modsScreen(breeze);
        assertEquals("modmenu:mods", mods.name);
        assertSame(breeze, mods.parent, "closing Mod Menu returns to Breeze");

        Map<String, Object> zoom = calls.factories("zoomify", new ZoomifyEntry());
        FakeScreen settings = (FakeScreen) calls.configScreen(zoom.get("zoomify"), breeze);
        assertEquals("zoomify:settings", settings.name);
        assertSame(breeze, settings.parent);

        Object none = calls.factories("badges", new BadgeOnlyEntry()).get("badges");
        assertNull(calls.configScreen(none, breeze), "Mod Menu's default factory has no screen; nothing is offered");

        assertTrue(calls.factories("modmenu", new ProvidesEntry()).containsKey("minecraft"));
    }

    @Test
    void withoutModMenuNothingIsCalled() {
        ClassLoader empty = new ClassLoader(null) {};
        assertThrows(ClassNotFoundException.class, () -> ModMenuApiCalls.find(empty, FakeScreen.class));
    }

    public static final class MapEntry {
        public String breezeLabel() { return "World map"; }
        public FakeScreen breezeOpen(FakeScreen parent) { return new FakeScreen("map", parent); }
    }

    public static final class NoOpen {
        public String breezeLabel() { return "Nothing"; }
    }

    @Test
    void anyModCanAddAnEntryWithTheBreezeEntrypoint() throws Exception {
        BreezeEntrypoint e = BreezeEntrypoint.of(new MapEntry(), FakeScreen.class);
        assertEquals("World map", e.label());
        FakeScreen parent = new FakeScreen("breeze", null);
        FakeScreen opened = (FakeScreen) e.open(parent);
        assertEquals("map", opened.name);
        assertSame(parent, opened.parent);
        NoSuchMethodException missing = assertThrows(NoSuchMethodException.class, () -> BreezeEntrypoint.of(new NoOpen(), FakeScreen.class));
        assertTrue(missing.getMessage().contains("breezeOpen"));
    }

    @Test
    void modsAreSortedIntoBreezeTheGameLibrariesAndMods() {
        LoadedMod breeze = new LoadedMod("breeze", "Breeze", "2.12.0", null, null, null, false, null);
        LoadedMod mc = new LoadedMod("minecraft", "Minecraft", "1.21.11", null, null, null, true, null);
        LoadedMod rinku = new LoadedMod("rinku", "Rinku", "3.0.4", null, null, "breeze", false, null);
        LoadedMod api = new LoadedMod("fabric-api", "Fabric API", "0.139.1", null, null, null, false, List.of("library"));
        LoadedMod zoom = new LoadedMod("zoomify", "Zoomify", "2.14.6", null, null, null, false, List.of());
        assertEquals(LoadedMod.Kind.BREEZE, breeze.kind());
        assertEquals(LoadedMod.Kind.GAME, mc.kind());
        assertEquals(LoadedMod.Kind.LIBRARY, rinku.kind());
        assertEquals(LoadedMod.Kind.LIBRARY, api.kind());
        assertEquals(LoadedMod.Kind.MOD, zoom.kind());

        Integration menu = new Integration("modmenu", "Mod Menu", "modmenu",
                List.of(new Integration.Action("mods", "All mods", null), new Integration.Action("config:zoomify", "Zoomify settings", "zoomify")));
        JsonObject report = RuntimeReport.json("2.12.0", "1.21.11", "0.19.5", List.of(breeze, mc, rinku, api, zoom), List.of(menu),
                List.of(new IntegrationProblem("modmenu", "xaeroworldmap", "NoClassDefFoundError: x")), 1L);
        assertEquals(5, report.getAsJsonArray("mods").size());
        assertEquals("library", report.getAsJsonArray("mods").get(2).getAsJsonObject().get("kind").getAsString());
        assertEquals("breeze", report.getAsJsonArray("mods").get(2).getAsJsonObject().get("parent").getAsString());
        assertEquals(2, report.getAsJsonArray("integrations").get(0).getAsJsonObject().get("actions").getAsInt());
        assertEquals("xaeroworldmap", report.getAsJsonArray("problems").get(0).getAsJsonObject().get("mod").getAsString());
    }

    @Test
    void aFailureIsOneReadableLine() {
        Exception e = new java.lang.reflect.InvocationTargetException(new NoClassDefFoundError("dev/isxander/yacl3/api/YetAnotherConfigLib\nat x"));
        assertEquals("NoClassDefFoundError: dev/isxander/yacl3/api/YetAnotherConfigLib at x", IntegrationProblem.describe(e));
    }
}
