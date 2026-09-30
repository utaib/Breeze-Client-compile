package dev.breeze.devtest;


import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.breeze.BreezeClient;
import dev.breeze.Module;
import dev.breeze.ModuleManager;
import dev.breeze.bridge.Router;
import dev.breeze.menu.BreezeMenuScreen;
import dev.breeze.web.BreezeBrowser;
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
                    "liveBrowsers", String.valueOf(BreezeBrowser.live()));
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
                            "ms", String.valueOf(age), "liveBrowsers", String.valueOf(BreezeBrowser.live()));
                    // Leave through a vanilla screen so removed() runs exactly as
                    // it does when a player opens Options from the menu.
                    dev.breeze.compat.ActiveScreen.set(mc, dev.breeze.compat.Screens.options(new TitleScreen(), mc));
                    next(Stage.STRESS_CLOSE, null);
                }
            }
            case STRESS_CLOSE -> {
                int live = BreezeBrowser.live();
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

    private enum World { IDLE, OPENED, IN_WORLD, EDITING, DONE }

    private static World world = World.IDLE;
    private static long worldAt;
    private static int capeLayerCalls;
    private static int capeCallsAtStart;
    private static int[] fpsBefore;
    private static int cosmeticDrawsAtStart;
    /** HUD elements that always have something to draw, switched on for the check. */
    private static final String[] HUD_CHECK = {"FPS", "Coordinates", "CPS", "Keystrokes", "Direction", "Inventory HUD"};

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
                    for (Module m : ModuleManager.getModules()) {
                        for (String name : HUD_CHECK) {
                            if (m.getName().equals(name) && !m.isEnabled()) m.setEnabled(true);
                        }
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
                    // A 3D cosmetic built in code, worn as a hat, without the API.
                    try {
                        dev.breeze.cosmetics.CosmeticModels.load("autotest-hat", null, TestModel.cubeGlb());
                        dev.breeze.cosmetics.WornCosmetics.setForTest(mc.player.getUUID(), java.util.List.of(
                                new dev.breeze.cosmetics.WornCosmetics.Worn("autotest-hat", "hat", "Test hat", "",
                                        dev.breeze.cosmetics.model.CosmeticRig.Attachment.HEAD,
                                        dev.breeze.cosmetics.model.CosmeticRig.Transform.NONE, java.util.Map.of(), null)));
                    } catch (Throwable t) {
                        log("cosmetic-setup", "error", t.toString());
                    }
                    mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
                    capeCallsAtStart = capeLayerCalls;
                    cosmeticDrawsAtStart = dev.breeze.cosmetics.CosmeticRender.draws;
                    world = World.IN_WORLD;
                    worldAt = System.currentTimeMillis();
                    log("world-joined");
                } else if (age > 300_000) {
                    log("FAIL", "reason", "the world never loaded");
                    world = World.DONE;
                }
            }
            case IN_WORLD -> {
                if (age < 5_000) return;
                shot(mc, "autotest-in-world");
                JsonObject hud = new JsonObject();
                boolean allDrawn = true;
                for (Module m : ModuleManager.getModules()) {
                    if (!(m instanceof dev.breeze.modules.AbstractHudModule h)) continue;
                    for (String name : HUD_CHECK) {
                        if (!m.getName().equals(name)) continue;
                        boolean recent = System.currentTimeMillis() - h.lastDrawnAt() < 2_000;
                        boolean ok = h.isEnabled() && recent && !h.drawFailed();
                        allDrawn &= ok;
                        hud.addProperty(name, (ok ? "drawn " : h.drawFailed() ? "threw " : "not drawn ")
                                + h.getHudW() + "x" + h.getHudH() + " at " + h.getHudX() + "," + h.getHudY());
                    }
                }
                hud.addProperty("pass", String.valueOf(allDrawn));
                write("hud-check", hud);

                String breeze = String.valueOf(dev.breeze.cape.RemoteCapes.capeFor(mc.player.getUUID()));
                String vanilla = String.valueOf(dev.breeze.compat.Capes.vanillaCape(mc.player));
                int layer = capeLayerCalls - capeCallsAtStart;
                boolean capeOk = !breeze.equals("null") && breeze.equals(vanilla) && layer > 0;
                log("cape-check", "breeze", breeze, "vanilla", vanilla, "layerCalls", String.valueOf(layer),
                        "pass", String.valueOf(capeOk));
                int cosmeticDraws = dev.breeze.cosmetics.CosmeticRender.draws - cosmeticDrawsAtStart;
                log("cosmetic-check", "draws", String.valueOf(cosmeticDraws), "pass", String.valueOf(cosmeticDraws > 0));

                mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
                for (Module m : ModuleManager.getModules()) {
                    if (m.getName().equals("FPS") && m instanceof dev.breeze.modules.AbstractHudModule h) {
                        fpsBefore = new int[]{h.getHudX(), h.getHudY()};
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
                String after = "none";
                String saved = "none";
                boolean moved = false;
                for (Module m : ModuleManager.getModules()) {
                    if (m.getName().equals("FPS") && m instanceof dev.breeze.modules.AbstractHudModule h) {
                        after = h.getHudX() + "," + h.getHudY();
                        dev.breeze.hud.HudPlacement p = dev.breeze.ui.HudLayout.get("FPS");
                        saved = String.valueOf(p);
                        moved = fpsBefore != null && p != null
                                && (Math.abs(h.getHudX() - fpsBefore[0]) + Math.abs(h.getHudY() - fpsBefore[1])) > 10;
                    }
                }
                log("hud-moved", "before", fpsBefore == null ? "none" : fpsBefore[0] + "," + fpsBefore[1],
                        "after", after, "saved", saved, "pass", String.valueOf(moved));
                world = World.DONE;
            }
            case DONE -> {
            }
        }
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
            for (GuiEventListener child : dev.breeze.compat.ActiveScreen.get(mc).children()) {
                if (!(child instanceof AbstractWidget w) || !w.visible) continue;
                JsonObject it = item(w.getMessage().getString(), "widget",
                        dev.breeze.compat.Widgets.x(w) + w.getWidth() / 2, dev.breeze.compat.Widgets.y(w) + w.getHeight() / 2, scale, w.active);
                // A text field's contents, so the driver can check that typing arrived.
                if (w instanceof EditBox box) it.addProperty("value", box.getValue());
                items.add(it);
            }
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
    private static void auditMixins() {
        long start = System.currentTimeMillis();
        try {
            org.spongepowered.asm.mixin.MixinEnvironment.getCurrentEnvironment().audit();
            log("mixin-audit", "ok", "true", "ms", String.valueOf(System.currentTimeMillis() - start));
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            // Recorded, not fatal here, so the rest of the run still shows what
            // else works; the driver counts it as a failed check.
            log("mixin-audit", "ok", "false", "error", t.toString(), "cause", root.toString());
        }
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
