package dev.breeze.devtest;


import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.breeze.BreezeClient;
import dev.breeze.Module;
import dev.breeze.ModuleManager;
import dev.breeze.bridge.Router;
import dev.breeze.menu.BreezeMenuScreen;
import dev.breeze.web.BreezeWebScreen;
import dev.breeze.web.WebInit;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Development self-test for the Breeze menu in a real Minecraft client.
 *
 * Inert unless the JVM is started with -Dbreeze.autotest=&lt;directory&gt;
 * (runClient -Pbreeze.autotest=..., or a real install's JVM arguments). It
 * opens no ports and accepts no input of its own; it only observes and
 * records, then runs an open/close leak check:
 *
 * <ol>
 *   <li>waits for the Breeze menu: the web menu where MCEF runs, or Minecraft's
 *       title screen with Breeze's buttons where there is no browser (the
 *       native mode). Records it and writes READY_FOR_INPUT, naming the mode,
 *       so an external driver can send real X11 mouse and keyboard input
 *       (scripts/ci/drive-minecraft.sh, drive-native.sh, with xdotool);</li>
 *   <li>records every screen change, the bridge actions the page sends, every
 *       module switched on or off, and, a moment after each screen opens, where
 *       its buttons are in window pixels (the "targets" event), so the driver
 *       can click by label and check what the game did;</li>
 *   <li>when the driver writes driver-done, opens and closes the menu 20 times
 *       and records how many browsers are live after each close and the heap;</li>
 *   <li>writes AUTOTEST_DONE and leaves the game running for the driver's
 *       last step (quitting through a menu).</li>
 * </ol>
 *
 * Every line goes to &lt;directory&gt;/breeze-autotest.log as JSON.
 */
public final class AutoTest {

    private enum Stage { WAIT_MENU, WAIT_PAINT, WAIT_DRIVER, STRESS_OPEN, STRESS_WAIT_PAINT, STRESS_CLOSE, DONE }

    private static Path dir;
    private static Stage stage = Stage.WAIT_MENU;
    private static long stageStart;
    private static String lastScreen = "";
    private static int cycle;
    private static int maxLiveAfterClose;
    private static boolean nativeMode;
    private static int targetsDue = -1;
    private static String lastTargets = "";
    private static final Map<String, Boolean> MODULES = new HashMap<>();
    private static boolean audited;
    private static final int CYCLES = 20;
    // A first start in a real install downloads MCEF's Chromium build before
    // the menu can open, so the wait is longer there.
    private static final long MENU_TIMEOUT_MS = Long.getLong("breeze.autotest.menuTimeoutSeconds", 240L) * 1000L;

    private AutoTest() {}

    /**
     * What a screen is, independent of class names. Released jars run with
     * Fabric's intermediary names (net.minecraft.class_525), so the log cannot
     * rely on Mojang's names; instanceof is remapped with the jar.
     */
    private static String kind(Screen s) {
        if (s == null) return "none";
        if (s instanceof BreezeWebScreen) return "breeze-web";
        if (s instanceof BreezeMenuScreen) return "breeze-native";
        if (s instanceof TitleScreen) return "title";
        if (s instanceof SelectWorldScreen) return "world-select";
        if (s instanceof CreateWorldScreen) return "create-world";
        if (s instanceof JoinMultiplayerScreen) return "multiplayer";
        if (s instanceof dev.breeze.menu.HudEditorScreen) return "hud-editor";
        if (s instanceof dev.breeze.menu.WardrobeScreen) return "wardrobe";
        if (s instanceof dev.breeze.menu.ModuleSettingsScreen) return "module-settings";
        if (s instanceof dev.breeze.menu.ThemeSettingsScreen) return "breeze-settings";
        if (s instanceof dev.breeze.menu.FriendsScreen) return "friends";
        if (s instanceof net.minecraft.client.gui.screens.PauseScreen) return "pause";
        if (dev.breeze.compat.Screens.isOptions(s)) return "options";
        return "other";
    }

    public static void install() {
        String target = System.getProperty("breeze.autotest");
        if (target == null || target.isBlank()) return;
        dir = Path.of(target).toAbsolutePath();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            BreezeClient.LOGGER.error("[Breeze autotest] cannot create {}", dir);
            return;
        }
        log("start", "dir", dir.toString());
        Targets.enable();
        if (STUB) {
            // The stand-in API (scripts/ci/stub-api.mjs) serves this as every
            // cosmetic's model.
            try {
                Files.write(dir.resolve("stub-model.glb"), TestModel.cubeGlb());
            } catch (Throwable t) {
                log("stub-model", "error", t.toString());
            }
        }
        Router.tap = (action, params) -> {
            JsonObject o = new JsonObject();
            o.addProperty("action", action);
            // Only non-personal parameters are recorded.
            if (action.equals("ui.route") || action.equals("ui.escapeAck") || action.equals("settings.set")
                    || action.equals("modules.setEnabled")) {
                o.add("params", params.raw());
            }
            write("bridge", o);
        };
        stageStart = System.currentTimeMillis();
        ClientTickEvents.END_CLIENT_TICK.register(AutoTest::tick);
    }

    private static void tick(Minecraft mc) {
        String screen = dev.breeze.compat.ActiveScreen.get(mc) == null ? "none" : dev.breeze.compat.ActiveScreen.get(mc).getClass().getName();
        if (!screen.equals(lastScreen)) {
            lastScreen = screen;
            log("screen", "class", screen, "kind", kind(dev.breeze.compat.ActiveScreen.get(mc)),
                    "liveBrowsers", String.valueOf(dev.breeze.web.BrowserCount.live()));
            Targets.clear();
            targetsDue = 10;
        }
        if (targetsDue > 0 && --targetsDue == 0) logTargets(mc, false);
        if (Files.exists(dir.resolve("targets-please"))) {
            try {
                Files.deleteIfExists(dir.resolve("targets-please"));
            } catch (IOException ignored) {
            }
            logTargets(mc, true);
        }
        watchModules();
        worldTick(mc);
        if (!audited && dev.breeze.compat.ActiveScreen.get(mc) != null && dev.breeze.compat.ActiveScreen.overlay(mc) == null) {
            audited = true;
            auditMixins();
        }

        long age = System.currentTimeMillis() - stageStart;
        switch (stage) {
            case WAIT_MENU -> {
                if (dev.breeze.compat.ActiveScreen.get(mc) instanceof BreezeWebScreen) {
                    next(Stage.WAIT_PAINT, "menu-open");
                } else if (WebInit.state() == WebInit.State.UNAVAILABLE && dev.breeze.compat.ActiveScreen.get(mc) instanceof TitleScreen
                        && dev.breeze.compat.ActiveScreen.overlay(mc) == null) {
                    // No embedded browser here: Minecraft's title screen with
                    // Breeze's buttons is the menu, and the native screens open
                    // from it.
                    nativeMode = true;
                    next(Stage.WAIT_PAINT, "menu-open");
                } else if (age > MENU_TIMEOUT_MS) {
                    fail("the Breeze title menu never opened; web state " + WebInit.state());
                }
            }
            case WAIT_PAINT -> {
                // Native: the title screen has been drawn with Breeze's button
                // on it (the loading overlay hides it until resources load).
                boolean ready = nativeMode
                        ? age > 1_000 && Targets.snapshot().containsKey("breeze-button")
                        : painted(mc);
                if (ready) {
                    shot(mc, "autotest-title-menu");
                    // Every module's Minecraft icon found in this version's
                    // own textures (or a later place to look).
                    write("module-icons", dev.breeze.ui.ModuleIconCache.report());
                    // Other mods Fabric loaded, and what Breeze can open from them.
                    write("integrations", dev.breeze.integrations.Integrations.json(mc, false));
                    logTargets(mc, true);
                    stage = Stage.WAIT_DRIVER;
                    stageStart = System.currentTimeMillis();
                    log("READY_FOR_INPUT", "mode", nativeMode ? "native" : "web");
                } else if (age > 60_000) {
                    fail("the page never painted");
                }
            }
            case WAIT_DRIVER -> {
                if (Files.exists(dir.resolve("driver-done"))) next(Stage.STRESS_OPEN, "stress-start");
                else if (age > 600_000) fail("the input driver never finished");
            }
            case STRESS_OPEN -> {
                dev.breeze.compat.ActiveScreen.set(mc, nativeMode ? new BreezeMenuScreen() : new BreezeWebScreen(false));
                next(Stage.STRESS_WAIT_PAINT, null);
            }
            case STRESS_WAIT_PAINT -> {
                boolean open = nativeMode ? dev.breeze.compat.ActiveScreen.get(mc) instanceof BreezeMenuScreen : painted(mc);
                if (open || age > 15_000) {
                    log("cycle-open", "cycle", String.valueOf(cycle), "painted", String.valueOf(open),
                            "ms", String.valueOf(age), "liveBrowsers", String.valueOf(dev.breeze.web.BrowserCount.live()));
                    // Leave through a vanilla screen so removed() runs exactly as
                    // it does when a player opens Options from the menu.
                    net.minecraft.client.gui.screens.Screen leaveTo = dev.breeze.compat.Screens.options(new TitleScreen(), mc);
                    if (cycle == 0) {
                        // Each settings screen Mod Menu offers is asked for once here.
                        write("integrations", dev.breeze.integrations.Integrations.json(mc, true));
                        if (dev.breeze.integrations.Integrations.modMenu()) {
                            // The first time, leave through Mod Menu's own list,
                            // opened the way the menu's Mod Menu entry opens it.
                            try {
                                leaveTo = dev.breeze.integrations.Integrations.screen("modmenu", "mods", dev.breeze.compat.ActiveScreen.get(mc));
                                log("integration-open", "id", "modmenu", "screen", leaveTo.getClass().getName(), "pass", "true");
                            } catch (dev.breeze.integrations.Integrations.Unavailable u) {
                                log("integration-open", "id", "modmenu", "error", u.getMessage(), "pass", "false");
                            }
                        }
                    }
                    dev.breeze.compat.ActiveScreen.set(mc, leaveTo);
                    next(Stage.STRESS_CLOSE, null);
                }
            }
            case STRESS_CLOSE -> {
                int live = dev.breeze.web.BrowserCount.live();
                maxLiveAfterClose = Math.max(maxLiveAfterClose, live);
                log("cycle-close", "cycle", String.valueOf(cycle), "liveBrowsers", String.valueOf(live),
                        "heapMb", String.valueOf(heapMb()));
                cycle++;
                if (cycle < CYCLES) {
                    next(Stage.STRESS_OPEN, null);
                } else {
                    System.gc();
                    log("stress-result", "cycles", String.valueOf(CYCLES), "maxLiveAfterClose",
                            String.valueOf(maxLiveAfterClose), "heapMb", String.valueOf(heapMb()),
                            "pass", String.valueOf(maxLiveAfterClose == 0));
                    dev.breeze.compat.ActiveScreen.set(mc, nativeMode ? new TitleScreen() : new BreezeWebScreen(false));
                    next(Stage.DONE, "AUTOTEST_DONE");
                }
            }
            case DONE -> {
            }
        }
    }

    // ── In a world (after the menu checks) ─────────────────────────────────

    private enum World { IDLE, OPENED, FPS, IN_WORLD, ARMOR, PERSONAL_CAPE, LAYOUT, SWEEP, EDITING, WARDROBE, REAL, DONE }

    /** Taken off again once the Armor HUD has been looked at. */
    private static boolean armourOff;

    /**
     * Frame rate in the new world before anything is switched on: one reading
     * of Minecraft's own FPS counter a second for ten seconds, after eight to
     * let the first chunks settle. Software rendered on the test machine, so
     * only comparable with other runs on the same kind of machine (for
     * instance with and without optimisation mods), never a player's FPS.
     */
    private static final java.util.List<Integer> fpsReadings = new java.util.ArrayList<>();
    private static long lastFpsReadingAt;

    /** Real cosmetics from the public catalogue, served by the stand-in API with "real-" ids. */
    private static final java.util.List<String> realIds = new java.util.ArrayList<>();
    /**
     * The real cosmetics in rounds: one per slot is worn at a time (as in the
     * API), so a second pet is worn in the next round, after the first.
     */
    private static final java.util.List<java.util.List<String>> realRounds = new java.util.ArrayList<>();
    private static int realRound;
    private static boolean deathLogged;
    private static int realStage;

    /** The test runs against the stand-in API, with a game token for a test player. */
    private static final boolean STUB = System.getProperty("breeze.autotest.stub") != null;
    private static final String STUB_HAT = "stub-hat";

    private static World world = World.IDLE;
    private static long worldAt;
    private static int capeLayerCalls;
    private static int capeCallsAtStart;
    /** Where each element the driver drags in the HUD editor was before. */
    private static final Map<String, int[]> dragBefore = new HashMap<>();
    private static ModuleSweep sweep;
    private static int cosmeticDrawsAtStart;
    private static int cosmeticFailuresAtStart;
    /** The test's 3D cosmetics: id, slot, attachment. */
    private static final Object[][] COSMETIC_CHECK = {
            {"autotest-hat", "hat", dev.breeze.cosmetics.model.CosmeticRig.Attachment.HEAD},
            {"autotest-pet", "pet", dev.breeze.cosmetics.model.CosmeticRig.Attachment.FLYING_PET},
            {"autotest-trail", "trail", dev.breeze.cosmetics.model.CosmeticRig.Attachment.TRAIL},
    };
    /**
     * HUD elements left on for the HUD editor step. The driver drags the first
     * three: text (FPS), boxes it draws itself (Keystrokes) and one anchored to
     * the right edge, under the editor's panel until it is hidden (Inventory).
     */
    private static final String[] EDITOR_SET = {"FPS", "Keystrokes", "Inventory HUD", "Coordinates", "CPS", "Direction"};
    private static final String[] DRAGGED = {"FPS", "Keystrokes", "Inventory HUD"};

    /** Called by the cape layer each time it runs (all three CapeLayerMixin forms). */
    public static void capeLayer() {
        if (dir != null) capeLayerCalls++;
    }

    /**
     * The driver writes world-please once the menu checks are done: this opens
     * Singleplayer (the driver creates the world), and once the player is in
     * it switches on HUD elements and the Custom Cape with a test image, looks
     * from behind, checks each element drew and the cape is the one Minecraft
     * would draw, then opens the HUD editor for the driver to drag an element.
     */
    private static void worldTick(Minecraft mc) {
        long age = System.currentTimeMillis() - worldAt;
        // A dead player is not drawn and the death screen covers everything,
        // so every later check would fail for that one reason: say it once.
        if (!deathLogged && world != World.IDLE && world != World.OPENED && world != World.DONE
                && mc.player != null && mc.player.getHealth() <= 0) {
            deathLogged = true;
            log("player-died", "during", world.name());
        }
        switch (world) {
            case IDLE -> {
                if (!Files.exists(dir.resolve("world-please"))) return;
                try {
                    Files.deleteIfExists(dir.resolve("world-please"));
                } catch (IOException ignored) {
                }
                dev.breeze.compat.ActiveScreen.set(mc, new SelectWorldScreen(dev.breeze.compat.ActiveScreen.get(mc)));
                world = World.OPENED;
                worldAt = System.currentTimeMillis();
                log("world-open");
            }
            case OPENED -> {
                if (mc.level != null && mc.player != null && dev.breeze.compat.ActiveScreen.get(mc) == null) {
                    // The test player stands still for minutes in a world
                    // from a random seed: one spawned in a dark forest was
                    // killed by mobs part way through (1.21.10, run
                    // 36834405326). Peaceful removes them and keeps the
                    // survival HUD (hearts, food) the checks look at.
                    net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
                    if (server != null) {
                        server.execute(() -> server.setDifficulty(net.minecraft.world.Difficulty.PEACEFUL, true));
                    }
                    fpsReadings.clear();
                    lastFpsReadingAt = 0;
                    world = World.FPS;
                    worldAt = System.currentTimeMillis();
                    log("world-loaded");
                } else if (age > 300_000) {
                    log("FAIL", "reason", "the world never loaded");
                    world = World.DONE;
                }
            }
            case FPS -> {
                if (age < 8_000) return;
                long now = System.currentTimeMillis();
                if (age < 18_000) {
                    if (now - lastFpsReadingAt >= 1_000) {
                        lastFpsReadingAt = now;
                        fpsReadings.add(dev.breeze.compat.Perf.fps(mc));
                    }
                    return;
                }
                if (!fpsReadings.isEmpty()) {
                    int sum = 0, min = Integer.MAX_VALUE, max = 0;
                    for (int f : fpsReadings) { sum += f; min = Math.min(min, f); max = Math.max(max, f); }
                    log("fps-sample", "avg", String.format(java.util.Locale.ROOT, "%.1f", sum / (double) fpsReadings.size()),
                            "min", String.valueOf(min), "max", String.valueOf(max),
                            "readings", String.valueOf(fpsReadings.size()),
                            "mods", String.valueOf(net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods().size()));
                }
                {
                    // Every HUD module on, for the draw check and the layout check.
                    for (dev.breeze.modules.AbstractHudModule h : HudSweep.huds()) {
                        if (!h.isEnabled()) h.setEnabled(true);
                    }
                    try {
                        Path capes = mc.gameDirectory.toPath().resolve("breeze_capes");
                        Files.createDirectories(capes);
                        // Blue with a light border, so it reads on a screenshot.
                        Files.write(capes.resolve("autotest-cape.png"), dev.breeze.cosmetics.Png.encode(64, 32,
                                (x, y) -> (x % 22 == 0 || y % 17 == 0) ? 0xFFE7E9EE : 0xFF2F6BD8));
                    } catch (Throwable t) {
                        log("cape-image", "error", t.toString());
                    }
                    for (Module m : ModuleManager.getModules()) {
                        if (m.getName().equals("Custom Cape")) {
                            if (m.isEnabled()) m.setEnabled(false);
                            m.setEnabled(true);
                        }
                    }
                    // 3D cosmetics built in code, without the API: a hat on the
                    // head, a flying pet (moves every frame) and a trail (fading
                    // copies, so the translucent path draws too).
                    try {
                        java.util.List<dev.breeze.cosmetics.WornCosmetics.Worn> worn = new java.util.ArrayList<>();
                        for (Object[] c : COSMETIC_CHECK) {
                            String id = (String) c[0];
                            dev.breeze.cosmetics.CosmeticModels.load(id, null, TestModel.cubeGlb());
                            worn.add(new dev.breeze.cosmetics.WornCosmetics.Worn(id, (String) c[1], id, "",
                                    (dev.breeze.cosmetics.model.CosmeticRig.Attachment) c[2],
                                    dev.breeze.cosmetics.model.CosmeticRig.Transform.NONE, java.util.Map.of(), null));
                        }
                        dev.breeze.cosmetics.WornCosmetics.setForTest(mc.player.getUUID(), worn);
                    } catch (Throwable t) {
                        log("cosmetic-setup", "error", t.toString());
                    }
                    mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
                    capeCallsAtStart = capeLayerCalls;
                    cosmeticDrawsAtStart = dev.breeze.cosmetics.CosmeticRender.draws;
                    cosmeticFailuresAtStart = dev.breeze.cosmetics.CosmeticRender.failures;
                    world = World.IN_WORLD;
                    worldAt = System.currentTimeMillis();
                    log("world-joined");
                }
            }
            case IN_WORLD -> {
                if (age < 5_000) return;
                shot(mc, "autotest-in-world");
                write("hud-check", HudSweep.check());

                // Tags are a Wind Charge icon: a character the mod's font files
                // map to an 8 pixel picture, so it is 9 wide with its spacing.
                // Without the font it would be the narrower missing-glyph box.
                int iconW = mc.font.width(dev.breeze.BreezeTag.ICON);
                log("tag-icon", "width", String.valueOf(iconW), "pass", String.valueOf(iconW == 9));

                String breeze = String.valueOf(dev.breeze.cape.RemoteCapes.capeFor(mc.player.getUUID()));
                String vanilla = String.valueOf(dev.breeze.compat.Capes.vanillaCape(mc.player));
                int layer = capeLayerCalls - capeCallsAtStart;
                boolean capeOk = !breeze.equals("null") && breeze.equals(vanilla) && layer > 0;
                log("cape-check", "breeze", breeze, "vanilla", vanilla, "layerCalls", String.valueOf(layer),
                        "source", capeSource(breeze), "stub", String.valueOf(STUB), "pass", String.valueOf(capeOk));
                int cosmeticDraws = dev.breeze.cosmetics.CosmeticRender.draws - cosmeticDrawsAtStart;
                int cosmeticFailures = dev.breeze.cosmetics.CosmeticRender.failures - cosmeticFailuresAtStart;
                JsonObject cos = new JsonObject();
                boolean eachDrawn = true;
                for (Object[] c : COSMETIC_CHECK) {
                    int n = dev.breeze.cosmetics.CosmeticRender.DRAWS_BY_ID.getOrDefault((String) c[0], 0);
                    eachDrawn &= n > 0;
                    cos.addProperty((String) c[0], String.valueOf(n));
                }
                cos.addProperty("draws", String.valueOf(cosmeticDraws));
                cos.addProperty("failures", String.valueOf(cosmeticFailures));
                cos.addProperty("pass", String.valueOf(cosmeticDraws > 0 && eachDrawn && cosmeticFailures == 0));
                write("cosmetic-check", cos);

                // The Armor HUD with real gear: worn diamond and iron armour,
                // a diamond sword and a shield, on its own so the screenshot
                // shows it, from behind so the player wears it too.
                equipArmour(mc, true);
                for (dev.breeze.modules.AbstractHudModule h : HudSweep.huds()) {
                    boolean keep = h.getName().equals("Armor Status");
                    if (h.isEnabled() != keep) h.setEnabled(keep);
                }
                armourOff = false;
                world = World.ARMOR;
                worldAt = System.currentTimeMillis();
            }
            case ARMOR -> {
                if (!armourOff) {
                    if (age < 4_000) return;
                    shot(mc, "autotest-armor-hud");
                    java.util.List<String> drawn = java.util.List.of();
                    for (Module m : ModuleManager.getModules()) {
                        if (m instanceof dev.breeze.modules.ArmorStatusHud a) drawn = a.lastDrawn();
                    }
                    // Six pieces: four armour, the sword, the shield. The
                    // chestplate is nearly worn out, so its number is red.
                    boolean six = drawn.size() == 6;
                    String chest = drawn.stream().filter(d -> d.startsWith("chest ")).findFirst().orElse("");
                    boolean red = false;
                    int hash = chest.lastIndexOf('#');
                    if (hash >= 0) {
                        int c = (int) Long.parseLong(chest.substring(hash + 1), 16);
                        red = ((c >> 16) & 0xFF) > 200 && ((c >> 8) & 0xFF) < 128;
                    }
                    boolean sword = drawn.stream().anyMatch(d -> d.startsWith("mainhand "));
                    boolean shield = drawn.stream().anyMatch(d -> d.startsWith("offhand "));
                    JsonObject o = new JsonObject();
                    o.addProperty("drawn", String.join(" | ", drawn));
                    o.addProperty("pass", String.valueOf(six && red && sword && shield));
                    write("armor-check", o);
                    equipArmour(mc, false);
                    for (dev.breeze.modules.AbstractHudModule h : HudSweep.huds()) {
                        if (!h.isEnabled()) h.setEnabled(true);
                    }
                    armourOff = true;
                    worldAt = System.currentTimeMillis();
                    return;
                }
                // Long enough for the empty slots to reach the client before
                // the layout step measures every element.
                if (age < 2_000) return;
                if (STUB) {
                    // The account now wears an uploaded (personal) cape, as
                    // many players do: the stand-in API answers "personal" on
                    // /selected and serves the image on /cape/:uuid. It has to
                    // replace the catalogue cape without a restart.
                    stubPost("/test/personal-cape");
                    world = World.PERSONAL_CAPE;
                    worldAt = System.currentTimeMillis();
                    return;
                }
                // Every element moved to its own place, saved, read back.
                HudSweep.place(mc);
                world = World.LAYOUT;
                worldAt = System.currentTimeMillis();
            }
            case PERSONAL_CAPE -> {
                String breeze = String.valueOf(dev.breeze.cape.RemoteCapes.capeFor(mc.player.getUUID()));
                String vanilla = String.valueOf(dev.breeze.compat.Capes.vanillaCape(mc.player));
                boolean drawn = breeze.contains("remote_capes/") && breeze.equals(vanilla);
                // The selection is asked again every 15 s, then the image downloads.
                if (!drawn && age < 45_000) return;
                log("cape-personal-check", "breeze", breeze, "vanilla", vanilla, "source", capeSource(breeze),
                        "seconds", String.valueOf(age / 1000), "pass", String.valueOf(drawn));
                shot(mc, "autotest-personal-cape");
                HudSweep.place(mc);
                world = World.LAYOUT;
                worldAt = System.currentTimeMillis();
            }
            case LAYOUT -> {
                if (age < 3_000) return;
                write("hud-layout", HudSweep.verify(mc));
                shot(mc, "autotest-hud-layout");
                HudSweep.restore();
                sweep = new ModuleSweep();
                world = World.SWEEP;
                worldAt = System.currentTimeMillis();
                log("module-sweep-start");
            }
            case SWEEP -> {
                if (!sweep.step(mc) && age < 600_000) return;
                write("module-sweep", sweep.report());
                write("settings-persist", ModuleSweep.persistence());

                // The HUD editor step: a few elements on, from their defaults.
                for (dev.breeze.modules.AbstractHudModule h : HudSweep.huds()) {
                    boolean keep = java.util.Arrays.asList(EDITOR_SET).contains(h.getName());
                    if (h.isEnabled() != keep) h.setEnabled(keep);
                }
                dev.breeze.compat.ActiveScreen.set(mc, null);
                mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
                dragBefore.clear();
                for (dev.breeze.modules.AbstractHudModule h : HudSweep.huds()) {
                    if (java.util.Arrays.asList(DRAGGED).contains(h.getName())) {
                        dragBefore.put(h.getName(), new int[]{h.getHudX(), h.getHudY()});
                    }
                }
                dev.breeze.compat.ActiveScreen.set(mc, new dev.breeze.menu.HudEditorScreen(null));
                world = World.EDITING;
                worldAt = System.currentTimeMillis();
                log("WORLD_READY");
            }
            case EDITING -> {
                if (dev.breeze.compat.ActiveScreen.get(mc) instanceof dev.breeze.menu.HudEditorScreen) {
                    if (age > 300_000) world = World.DONE;
                    return;
                }
                JsonObject moves = new JsonObject();
                boolean allMoved = true;
                for (dev.breeze.modules.AbstractHudModule h : HudSweep.huds()) {
                    int[] before = dragBefore.get(h.getName());
                    if (before == null) continue;
                    dev.breeze.hud.HudPlacement p = dev.breeze.ui.HudLayout.get(h.getName());
                    boolean moved = p != null
                            && (Math.abs(h.getHudX() - before[0]) + Math.abs(h.getHudY() - before[1])) > 10;
                    allMoved &= moved;
                    moves.addProperty(h.getName(), before[0] + "," + before[1] + " -> " + h.getHudX() + "," + h.getHudY()
                            + " saved " + p + (moved ? "" : " (not moved)"));
                }
                moves.addProperty("pass", String.valueOf(allMoved && !dragBefore.isEmpty()));
                write("hud-moved", moves);
                if (!STUB) {
                    world = World.DONE;
                    return;
                }
                // Equip a 3D cosmetic the way a player does: the Wardrobe's 3D
                // tab, a click on the row (the driver's real mouse), then the
                // API, then the model drawn on the player.
                dev.breeze.cosmetics.WornCosmetics.clearTest(mc.player.getUUID());
                dev.breeze.cosmetics.OwnedModels.refresh(true);
                dev.breeze.compat.ActiveScreen.set(mc, new dev.breeze.menu.WardrobeScreen(null, "3D"));
                world = World.WARDROBE;
                worldAt = System.currentTimeMillis();
                log("wardrobe-open");
            }
            case WARDROBE -> {
                boolean listed = false, equipped = false;
                for (dev.breeze.cosmetics.OwnedCosmetics.Item i : dev.breeze.cosmetics.OwnedModels.items()) {
                    if (i.id.equals(STUB_HAT)) {
                        listed = true;
                        equipped = i.equipped;
                    }
                }
                boolean worn = false;
                for (dev.breeze.cosmetics.WornCosmetics.Worn w : dev.breeze.cosmetics.WornCosmetics.get(mc.player.getUUID())) {
                    if (w.id.equals(STUB_HAT)) worn = true;
                }
                int draws = dev.breeze.cosmetics.CosmeticRender.DRAWS_BY_ID.getOrDefault(STUB_HAT, 0);
                boolean pass = listed && equipped && worn && draws > 0;
                if (!pass && age < 60_000) return;
                if (pass) shot(mc, "autotest-wardrobe-equipped");
                log("wardrobe-check", "status", String.valueOf(dev.breeze.cosmetics.OwnedModels.status()),
                        "listed", String.valueOf(listed), "equipped", String.valueOf(equipped),
                        "worn", String.valueOf(worn), "draws", String.valueOf(draws), "pass", String.valueOf(pass));
                dev.breeze.compat.ActiveScreen.set(mc, null);
                // Then the real cosmetics, if the catalogue could be read.
                realIds.clear();
                realRounds.clear();
                java.util.Map<String, Integer> perSlot = new java.util.HashMap<>();
                for (dev.breeze.cosmetics.OwnedCosmetics.Item i : dev.breeze.cosmetics.OwnedModels.items()) {
                    if (!i.id.startsWith("real-")) continue;
                    realIds.add(i.id);
                    int round = perSlot.merge(i.slot, 1, Integer::sum) - 1;
                    while (realRounds.size() <= round) realRounds.add(new java.util.ArrayList<>());
                    realRounds.get(round).add(i.id);
                }
                realRound = 0;
                if (!realRounds.isEmpty()) {
                    for (String id : realRounds.get(0)) dev.breeze.cosmetics.OwnedModels.equip(id, null);
                }
                if (realIds.isEmpty()) {
                    log("real-cosmetics", "pass", "skipped", "reason", "no real cosmetics to wear (see the [cosmetics] lines: catalogue not reached, or no model could be fetched)");
                    world = World.DONE;
                    return;
                }
                mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
                realStage = 0;
                world = World.REAL;
                worldAt = System.currentTimeMillis();
                log("real-cosmetics-start", "ids", String.join(",", realIds), "rounds", String.valueOf(realRounds.size()));
            }
            case REAL -> {
                // Each real model of this round drawn on the player, or a
                // reason it was not.
                boolean resolved = true;
                for (String id : realRounds.get(realRound)) {
                    boolean drawn = dev.breeze.cosmetics.CosmeticRender.DRAWS_BY_ID.getOrDefault(id, 0) > 0;
                    if (!drawn && dev.breeze.cosmetics.CosmeticModels.failure(id) == null) resolved = false;
                }
                if (realStage == 0) {
                    if ((!resolved || age < 8_000) && age < 90_000) return;
                    shot(mc, "autotest-real-cosmetics" + (realRound == 0 ? "" : "-" + (realRound + 1)));
                    if (realRound + 1 < realRounds.size()) {
                        // The next round replaces this one's in each slot.
                        realRound++;
                        for (String id : realRounds.get(realRound)) dev.breeze.cosmetics.OwnedModels.equip(id, null);
                        log("real-cosmetics-round", "round", String.valueOf(realRound + 1),
                                "ids", String.join(",", realRounds.get(realRound)));
                        worldAt = System.currentTimeMillis();
                        return;
                    }
                    mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
                    realStage = 1;
                    worldAt = System.currentTimeMillis();
                    return;
                }
                if (age < 3_000) return;
                shot(mc, "autotest-real-cosmetics-front");
                JsonObject o = new JsonObject();
                boolean all = true;
                for (String id : realIds) {
                    int n = dev.breeze.cosmetics.CosmeticRender.DRAWS_BY_ID.getOrDefault(id, 0);
                    String failure = dev.breeze.cosmetics.CosmeticModels.failure(id);
                    String name = id;
                    for (dev.breeze.cosmetics.OwnedCosmetics.Item i : dev.breeze.cosmetics.OwnedModels.items()) {
                        if (i.id.equals(id)) name = i.slot + " " + i.name;
                    }
                    // Two cosmetics can share a name; each gets its own line.
                    if (o.has(name)) name = name + " (" + id.substring(Math.max(0, id.length() - 8)) + ")";
                    all &= n > 0 && failure == null;
                    o.addProperty(name, n > 0 ? "drawn " + n + "x" : failure != null ? "failed: " + failure : "not drawn");
                }
                o.addProperty("failures", String.valueOf(dev.breeze.cosmetics.CosmeticRender.failures));
                o.addProperty("pass", String.valueOf(all));
                write("real-cosmetics", o);
                mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
                world = World.DONE;
            }
            case DONE -> {
            }
        }
    }

    /**
     * Puts worn gear on the test player through the game's own server (or
     * takes it off): a diamond helmet, a nearly broken iron chestplate,
     * diamond leggings, golden boots, a half-used diamond sword and a shield.
     */
    private static void equipArmour(Minecraft mc, boolean on) {
        net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) return;
        java.util.UUID id = mc.player.getUUID();
        server.execute(() -> {
            net.minecraft.server.level.ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) return;
            p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, worn(on, net.minecraft.world.item.Items.DIAMOND_HELMET, 0.25f));
            p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, worn(on, net.minecraft.world.item.Items.IRON_CHESTPLATE, 0.9f));
            p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS, worn(on, net.minecraft.world.item.Items.DIAMOND_LEGGINGS, 0.6f));
            p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET, worn(on, net.minecraft.world.item.Items.GOLDEN_BOOTS, 0f));
            p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, worn(on, net.minecraft.world.item.Items.DIAMOND_SWORD, 0.5f));
            p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, worn(on, net.minecraft.world.item.Items.SHIELD, 0.1f));
        });
    }

    private static net.minecraft.world.item.ItemStack worn(boolean on, net.minecraft.world.item.Item item, float used) {
        if (!on) return net.minecraft.world.item.ItemStack.EMPTY;
        net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(item);
        stack.setDamageValue((int) (stack.getMaxDamage() * used));
        return stack;
    }

    /**
     * Where the current screen's controls are, in window pixels: Minecraft's
     * widgets by their label, and the points Breeze's own screens publish
     * ({@link Targets}). Logged when it changes, or always when forced.
     */
    private static void logTargets(Minecraft mc, boolean force) {
        double scale = mc.getWindow().getGuiScale();
        JsonArray items = new JsonArray();
        if (dev.breeze.compat.ActiveScreen.get(mc) != null) {
            addWidgets(dev.breeze.compat.ActiveScreen.get(mc).children(), scale, items, 0);
        }
        for (Map.Entry<String, int[]> e : Targets.snapshot().entrySet()) {
            items.add(item(e.getKey(), "target", e.getValue()[0], e.getValue()[1], scale, true));
        }
        JsonObject o = new JsonObject();
        o.addProperty("kind", kind(dev.breeze.compat.ActiveScreen.get(mc)));
        o.addProperty("scale", scale);
        o.add("items", items);
        String key = o.toString();
        if (!force && key.equals(lastTargets)) return;
        lastTargets = key;
        write("targets", o);
    }

    private static void addWidgets(java.util.List<? extends GuiEventListener> children, double scale, JsonArray items, int depth) {
        for (GuiEventListener child : children) {
            if (!(child instanceof AbstractWidget w) || !w.visible) continue;
            JsonObject it = item(w.getMessage().getString(), "widget",
                    dev.breeze.compat.Widgets.x(w) + w.getWidth() / 2, dev.breeze.compat.Widgets.y(w) + w.getHeight() / 2, scale, w.active);
            // A text field's contents, so the driver can check that typing arrived.
            if (w instanceof EditBox box) it.addProperty("value", box.getValue());
            items.add(it);
            // A layout widget holds its buttons inside (1.19.3's pause menu is
            // one GridWidget); list those too.
            if (depth < 3 && w instanceof net.minecraft.client.gui.components.events.ContainerEventHandler c) {
                addWidgets(c.children(), scale, items, depth + 1);
            }
        }
    }

    private static JsonObject item(String name, String type, int guiX, int guiY, double scale, boolean active) {
        JsonObject i = new JsonObject();
        i.addProperty("name", name);
        i.addProperty("type", type);
        i.addProperty("x", (int) Math.round(guiX * scale));
        i.addProperty("y", (int) Math.round(guiY * scale));
        i.addProperty("active", active);
        return i;
    }

    /**
     * Applies every mixin now instead of when its target class first loads.
     * Some targets (the local player, the network handler) only load inside a
     * world, so a mixin that no longer fits this Minecraft version would
     * otherwise stay hidden until a player joins one. Mixin's audit loads each
     * remaining target; a required injection that cannot apply throws here.
     */
    /**
     * Loads every class a Breeze mixin targets, so each Breeze mixin is applied
     * now rather than when play first reaches it, and a mixin that does not fit
     * this version fails at the title screen. Only Breeze's: Mixin's own
     * audit() also applies other mods' optional mixin sets that they never
     * load in play (Flashback's Lattice ships one per Minecraft version), and
     * failed on those. Another mod's failure is recorded, not counted.
     */
    private static void auditMixins() {
        long start = System.currentTimeMillis();
        List<String> breeze = new ArrayList<>();
        List<String> others = new ArrayList<>();
        int checked = 0;
        try {
            // Breeze's own config, read from its jar. Mixins.getConfigs() only
            // lists configs Mixin has not consumed yet, which after start is
            // none, so the audit used to check nothing.
            for (String target : breezeMixinTargets()) {
                checked++;
                try {
                    Class.forName(target.replace('/', '.'), true, AutoTest.class.getClassLoader());
                } catch (Throwable t) {
                    Throwable root = t;
                    while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                    String text = target + ": " + root;
                    (text.contains("breeze") ? breeze : others).add(text);
                }
            }
        } catch (Throwable t) {
            breeze.add("could not list Breeze's mixin targets: " + t);
        }
        JsonObject o = new JsonObject();
        o.addProperty("ok", String.valueOf(breeze.isEmpty() && checked > 0));
        o.addProperty("targets", String.valueOf(checked));
        o.addProperty("ms", String.valueOf(System.currentTimeMillis() - start));
        if (!breeze.isEmpty()) o.addProperty("error", String.join(" | ", breeze));
        if (!others.isEmpty()) o.addProperty("otherMods", String.join(" | ", others));
        // Recorded, not fatal here, so the rest of the run still shows what
        // else works; the driver counts it as a failed check.
        write("mixin-audit", o);
    }

    /**
     * The classes Breeze's mixins target: breeze.mixins.json names the mixin
     * classes, and each one's @Mixin annotation (read from its bytes with
     * ASM, never loaded) names its targets, in the names this game runs with.
     */
    private static java.util.Set<String> breezeMixinTargets() throws java.io.IOException {
        ClassLoader loader = AutoTest.class.getClassLoader();
        JsonObject config;
        try (java.io.InputStream in = loader.getResourceAsStream("breeze.mixins.json")) {
            if (in == null) throw new java.io.FileNotFoundException("breeze.mixins.json");
            config = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        }
        String pkg = config.get("package").getAsString();
        java.util.Set<String> targets = new java.util.TreeSet<>();
        for (String side : new String[] {"mixins", "client"}) {
            if (!config.has(side)) continue;
            for (com.google.gson.JsonElement name : config.getAsJsonArray(side)) {
                String path = (pkg + "." + name.getAsString()).replace('.', '/') + ".class";
                try (java.io.InputStream in = loader.getResourceAsStream(path)) {
                    if (in == null) throw new java.io.FileNotFoundException(path);
                    org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
                    new org.objectweb.asm.ClassReader(in).accept(node, org.objectweb.asm.ClassReader.SKIP_CODE);
                    for (org.objectweb.asm.tree.AnnotationNode a : node.invisibleAnnotations == null
                            ? List.<org.objectweb.asm.tree.AnnotationNode>of() : node.invisibleAnnotations) {
                        if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(a.desc) || a.values == null) continue;
                        for (int i = 0; i + 1 < a.values.size(); i += 2) {
                            Object key = a.values.get(i);
                            if (!(a.values.get(i + 1) instanceof List<?> list)) continue;
                            for (Object v : list) {
                                if ("value".equals(key) && v instanceof org.objectweb.asm.Type t) targets.add(t.getClassName());
                                if ("targets".equals(key) && v instanceof String n) targets.add(n.replace('/', '.'));
                            }
                        }
                    }
                }
            }
        }
        return targets;
    }

    /** Logs every module that changes state, whoever changed it. */
    private static void watchModules() {
        boolean first = MODULES.isEmpty();
        for (Module m : ModuleManager.getModules()) {
            Boolean was = MODULES.put(m.getName(), m.isEnabled());
            if (!first && was != null && was != m.isEnabled()) {
                log("module", "name", m.getName(), "enabled", String.valueOf(m.isEnabled()));
            }
        }
    }

    private static boolean painted(Minecraft mc) {
        return dev.breeze.compat.ActiveScreen.get(mc) instanceof BreezeWebScreen s && s.painted();
    }

    private static long heapMb() {
        Runtime r = Runtime.getRuntime();
        return (r.totalMemory() - r.freeMemory()) / (1024 * 1024);
    }

    private static void shot(Minecraft mc, String name) {
        try {
            dev.breeze.compat.Game.screenshot(mc, name + ".png", msg -> {});
            log("screenshot", "file", "screenshots/" + name + ".png");
        } catch (Throwable t) {
            log("screenshot-failed", "error", t.toString());
        }
    }

    private static void next(Stage s, String event) {
        stage = s;
        stageStart = System.currentTimeMillis();
        if (event != null) log(event);
    }

    /** A key that reached the Breeze menu, recorded only while the self-test runs. */
    public static void key(int code) {
        if (dir != null) log("key", "code", String.valueOf(code));
    }

    private static void fail(String why) {
        log("FAIL", "reason", why);
        stage = Stage.DONE;
    }

    /**
     * A mouse press as the game and a Breeze screen saw it, for the driver's
     * log when a click does not do what it should. Does nothing unless the
     * self-test is running.
     */
    public static void mouse(String where, double x, double y, int button, String result) {
        if (dir == null) return;
        log("mouse", "where", where, "x", String.valueOf((int) x), "y", String.valueOf((int) y),
                "button", String.valueOf(button), "result", result);
    }

    /**
     * Where a drawn cape came from: the account's catalogue cape (server_capes,
     * through /selected and /capefile), its personal cape (remote_capes,
     * through /cape/:uuid) or the local Custom Cape.
     */
    private static String capeSource(String texture) {
        if (texture.contains("server_capes/")) return "account";
        if (texture.contains("remote_capes/")) return "personal";
        return texture.contains("capes/frame_") ? "local" : "other";
    }

    /** Tells the stand-in API to change what it answers. Test runs only. */
    private static void stubPost(String path) {
        try {
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(dev.breeze.net.BreezeApi.uri(path))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build();
            dev.breeze.net.BreezeApi.http().sendAsync(req, java.net.http.HttpResponse.BodyHandlers.discarding())
                    .whenComplete((res, err) -> log("stub-post", "path", path,
                            "status", res == null ? "none" : String.valueOf(res.statusCode()),
                            "error", err == null ? "" : err.toString()));
        } catch (Throwable t) {
            log("stub-post", "path", path, "error", t.toString());
        }
    }

    private static void log(String event, String... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i + 1 < kv.length; i += 2) o.addProperty(kv[i], kv[i + 1]);
        write(event, o);
    }

    private static synchronized void write(String event, JsonObject o) {
        o.addProperty("event", event);
        o.addProperty("t", System.currentTimeMillis());
        String line = o.toString();
        BreezeClient.LOGGER.info("[Breeze autotest] {}", line);
        try {
            Files.writeString(dir.resolve("breeze-autotest.log"), line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }
}
