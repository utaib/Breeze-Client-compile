package dev.breeze.devtest;

import com.google.gson.JsonObject;
import dev.breeze.BreezeClient;
import dev.breeze.bridge.Router;
import dev.breeze.web.BreezeBrowser;
import dev.breeze.web.BreezeWebScreen;
import dev.breeze.web.WebInit;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.OptionsScreen;
import net.minecraft.client.gui.screens.TitleScreen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Development self-test for the web menu in a real Minecraft client.
 *
 * Inert unless the JVM is started with -Dbreeze.autotest=&lt;directory&gt;
 * (runClient -Pbreeze.autotest=...). It opens no ports and accepts no input of
 * its own; it only observes and records, then runs an open/close leak check:
 *
 * <ol>
 *   <li>waits for the Breeze title menu to paint, records it, and writes
 *       READY_FOR_INPUT so an external driver can send real X11 mouse and
 *       keyboard input (scripts/ci/drive-minecraft.sh uses xdotool);</li>
 *   <li>records every bridge action the page sends and every screen change,
 *       so the driver's clicks can be checked against what the game did;</li>
 *   <li>when the driver writes driver-done, opens and closes the menu 20 times
 *       and records how many browsers are live after each close and the heap;</li>
 *   <li>writes AUTOTEST_DONE and leaves the game running for the driver's
 *       last step (quitting through the menu's own Quit button).</li>
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
    private static final int CYCLES = 20;

    private AutoTest() {}

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
        String screen = mc.screen == null ? "none" : mc.screen.getClass().getName();
        if (!screen.equals(lastScreen)) {
            lastScreen = screen;
            log("screen", "class", screen, "liveBrowsers", String.valueOf(BreezeBrowser.live()));
        }
        long age = System.currentTimeMillis() - stageStart;
        switch (stage) {
            case WAIT_MENU -> {
                if (mc.screen instanceof BreezeWebScreen) next(Stage.WAIT_PAINT, "menu-open");
                else if (age > 240_000) fail("the Breeze title menu never opened; web state " + WebInit.state());
            }
            case WAIT_PAINT -> {
                if (painted(mc)) {
                    shot(mc, "autotest-title-menu");
                    next(Stage.WAIT_DRIVER, "READY_FOR_INPUT");
                } else if (age > 60_000) {
                    fail("the page never painted");
                }
            }
            case WAIT_DRIVER -> {
                if (Files.exists(dir.resolve("driver-done"))) next(Stage.STRESS_OPEN, "stress-start");
                else if (age > 600_000) fail("the input driver never finished");
            }
            case STRESS_OPEN -> {
                mc.setScreen(new BreezeWebScreen(false));
                next(Stage.STRESS_WAIT_PAINT, null);
            }
            case STRESS_WAIT_PAINT -> {
                if (painted(mc) || age > 15_000) {
                    log("cycle-open", "cycle", String.valueOf(cycle), "painted", String.valueOf(painted(mc)),
                            "ms", String.valueOf(age), "liveBrowsers", String.valueOf(BreezeBrowser.live()));
                    // Leave through a vanilla screen so removed() runs exactly as
                    // it does when a player opens Options from the menu.
                    mc.setScreen(new OptionsScreen(new TitleScreen(), mc.options));
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
                    mc.setScreen(new BreezeWebScreen(false));
                    next(Stage.DONE, "AUTOTEST_DONE");
                }
            }
            case DONE -> {
            }
        }
    }

    private static boolean painted(Minecraft mc) {
        return mc.screen instanceof BreezeWebScreen s && s.painted();
    }

    private static long heapMb() {
        Runtime r = Runtime.getRuntime();
        return (r.totalMemory() - r.freeMemory()) / (1024 * 1024);
    }

    private static void shot(Minecraft mc, String name) {
        try {
            Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), msg -> {});
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
