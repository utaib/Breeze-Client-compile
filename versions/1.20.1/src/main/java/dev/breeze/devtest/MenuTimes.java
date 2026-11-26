package dev.breeze.devtest;

import dev.breeze.menu.BreezeMenuScreen;
import dev.breeze.web.BreezeWebScreen;
import dev.breeze.web.WarmBrowser;
import dev.breeze.web.WebInit;
import dev.breeze.ui.OpenTimer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;

/**
 * How long the menus take in a world, with the singleplayer server running
 * (the owner's laptop, 2026-11-20: Right Shift showed grey for 6.6 s, and the
 * game menu was slow to come back). Every time runs from the call the key
 * makes to the end of the first frame that shows the screen
 * ({@link OpenTimer}, at the tail of the game's frame):
 *
 * <ul>
 * <li>three opens of the Breeze menu as Right Shift opens it, with the
 * browser kept loaded: the page drawn, never the placeholder's fallback;</li>
 * <li>Escape in the menu back to the game;</li>
 * <li>Escape in the game to the game menu (Minecraft's pause);</li>
 * <li>the menu's Game menu action to the game menu;</li>
 * <li>an open with no browser kept (freed as the idle limit frees it): the
 * page or, after a second, the native menu;</li>
 * <li>what the kept browser costs: the Java heap, Chromium's processes
 * (Linux: the game's child processes' resident memory), the frame rate
 * with it hidden and with none.</li>
 * </ul>
 */
final class MenuTimes {

    private enum Step { WAIT_WARM, HIDDEN_FPS, OPEN, OPENED, ESCAPE, PAUSE, PAUSED, WEB_FOR_PAUSE, WEB_TO_PAUSE, PAUSED2,
        FREE, NONE_FPS, COLD, COLD_SHOWN, REWARM, DONE }

    private static final long STEP_TIMEOUT_MS = 15_000;
    private static Step step;
    private static long stepAt;
    private static int opens;
    private static final List<String> openMs = new ArrayList<>();
    private static final List<String> escapeMs = new ArrayList<>();
    private static boolean allWeb = true;
    private static long pauseMs = -1, webToPauseMs = -1, coldMs = -1, warmReadyMs = -1, rewarmMs = -1;
    private static String coldShown = "none";
    private static long heapWarm, heapNone, rssWarm = -1, rssNone = -1;
    private static long framesAt;
    private static double fpsHidden, fpsNone;
    private static int maxLive;
    private static long started;

    private MenuTimes() {}

    /** False (and nothing measured) where the web menu cannot open: the native menus have no browser to keep. */
    static boolean start(Minecraft mc) {
        if (WebInit.state() != WebInit.State.READY || !dev.breeze.ui.BreezeUi.webAvailable()) {
            AutoTest.report("menu-times", "pass", "skipped", "why", "no web menu here (" + WebInit.state() + ")");
            return false;
        }
        dev.breeze.compat.ActiveScreen.set(mc, null);
        started = System.currentTimeMillis();
        WarmBrowser.allowAgain();
        go(Step.WAIT_WARM);
        return true;
    }

    private static void go(Step s) {
        step = s;
        stepAt = System.currentTimeMillis();
    }

    /** True once done. */
    static boolean step(Minecraft mc) {
        maxLive = Math.max(maxLive, dev.breeze.web.BrowserCount.live());
        long age = System.currentTimeMillis() - stepAt;
        Screen screen = dev.breeze.compat.ActiveScreen.get(mc);
        switch (step) {
            case WAIT_WARM -> {
                if (!WarmBrowser.ready() && age < 90_000) return false;
                warmReadyMs = WarmBrowser.ready() ? System.currentTimeMillis() - started : -1;
                if (age < 2_000) return false; // a moment for the hidden page to settle
                System.gc();
                heapWarm = heapMb();
                rssWarm = ProcMem.childrenRssMb();
                framesAt = OpenTimer.frames;
                go(Step.HIDDEN_FPS);
            }
            case HIDDEN_FPS -> {
                if (age < 4_000) return false;
                fpsHidden = (OpenTimer.frames - framesAt) * 1000.0 / age;
                go(Step.OPEN);
            }
            case OPEN -> {
                if (age < 700) return false;
                long t0 = System.nanoTime();
                dev.breeze.ui.BreezeUi.open(mc);
                OpenTimer.expect("menu", t0, s -> (s instanceof BreezeWebScreen w && w.painted()) || s instanceof BreezeMenuScreen);
                go(Step.OPENED);
            }
            case OPENED -> {
                if (OpenTimer.waiting() && age < STEP_TIMEOUT_MS) return false;
                if (openMs.size() == opens) {
                    OpenTimer.cancel();
                    boolean web = screen instanceof BreezeWebScreen w && w.painted();
                    allWeb &= web;
                    openMs.add((web ? "" : "native ") + OpenTimer.result());
                }
                if (age < 700) return false;
                opens++;
                if (screen instanceof BreezeWebScreen w) {
                    long t0 = System.nanoTime();
                    w.testEscape();
                    OpenTimer.expect("escape", t0, s -> s == null);
                } else {
                    // The native menu: no page to answer Escape.
                    OpenTimer.cancel();
                    dev.breeze.compat.ActiveScreen.set(mc, null);
                    escapeMs.add("native");
                    go(opens < 3 ? Step.OPEN : Step.PAUSE);
                    return false;
                }
                go(Step.ESCAPE);
            }
            case ESCAPE -> {
                if (OpenTimer.waiting() && age < STEP_TIMEOUT_MS) return false;
                OpenTimer.cancel();
                escapeMs.add(String.valueOf(OpenTimer.result()));
                if (screen != null) dev.breeze.compat.ActiveScreen.set(mc, null);
                go(opens < 3 ? Step.OPEN : Step.PAUSE);
            }
            case PAUSE -> {
                if (age < 1_000) return false;
                long t0 = System.nanoTime();
                mc.pauseGame(false);
                OpenTimer.expect("game menu", t0, s -> s instanceof PauseScreen);
                go(Step.PAUSED);
            }
            case PAUSED -> {
                if (OpenTimer.waiting() && age < STEP_TIMEOUT_MS) return false;
                OpenTimer.cancel();
                pauseMs = OpenTimer.result();
                if (age < 1_000) return false;
                dev.breeze.compat.ActiveScreen.set(mc, null);
                go(Step.WEB_FOR_PAUSE);
            }
            case WEB_FOR_PAUSE -> {
                if (age < 1_000) return false;
                dev.breeze.ui.BreezeUi.open(mc);
                go(Step.WEB_TO_PAUSE);
            }
            case WEB_TO_PAUSE -> {
                boolean up = screen instanceof BreezeWebScreen w && w.painted();
                if (!up && age < STEP_TIMEOUT_MS) return false;
                if (age < 1_000) return false;
                // What the menu's Game menu action does (game.pauseMenu).
                long t0 = System.nanoTime();
                dev.breeze.compat.ActiveScreen.set(mc, new PauseScreen(true));
                OpenTimer.expect("menu to game menu", t0, s -> s instanceof PauseScreen);
                go(Step.PAUSED2);
            }
            case PAUSED2 -> {
                if (OpenTimer.waiting() && age < STEP_TIMEOUT_MS) return false;
                OpenTimer.cancel();
                webToPauseMs = OpenTimer.result();
                if (age < 1_000) return false;
                dev.breeze.compat.ActiveScreen.set(mc, null);
                go(Step.FREE);
            }
            case FREE -> {
                if (age < 1_500) return false;
                WarmBrowser.freeForTest();
                go(Step.NONE_FPS);
            }
            case NONE_FPS -> {
                // Chromium's processes have had 3 s to go.
                if (age < 3_000) return false;
                System.gc();
                heapNone = heapMb();
                rssNone = ProcMem.childrenRssMb();
                framesAt = OpenTimer.frames;
                fpsNone = -1;
                go(Step.COLD);
            }
            case COLD -> {
                if (fpsNone < 0) {
                    if (age < 4_000) return false;
                    fpsNone = (OpenTimer.frames - framesAt) * 1000.0 / age;
                    long t0 = System.nanoTime();
                    dev.breeze.ui.BreezeUi.open(mc);
                    OpenTimer.expect("menu, no browser kept", t0, s -> (s instanceof BreezeWebScreen w && w.painted()) || s instanceof BreezeMenuScreen);
                    go(Step.COLD_SHOWN);
                }
            }
            case COLD_SHOWN -> {
                if (OpenTimer.waiting() && age < STEP_TIMEOUT_MS) return false;
                OpenTimer.cancel();
                coldMs = OpenTimer.result();
                coldShown = screen instanceof BreezeWebScreen ? "web" : screen instanceof BreezeMenuScreen ? "native" : "none";
                if (age < 1_500) return false;
                dev.breeze.compat.ActiveScreen.set(mc, null);
                go(Step.REWARM);
            }
            case REWARM -> {
                // The browser that open made goes on loading and is kept.
                if (!WarmBrowser.ready() && age < 90_000) return false;
                rewarmMs = WarmBrowser.ready() ? age : -1;
                boolean pass = allWeb && opens == 3 && maxLive <= 1 && pauseMs >= 0 && webToPauseMs >= 0
                        && escapeMs.stream().noneMatch(x -> x.equals("-1")) && !coldShown.equals("none");
                AutoTest.report("menu-times",
                        "opens", String.join(",", openMs), "escapes", String.join(",", escapeMs),
                        "pauseMs", String.valueOf(pauseMs), "menuToPauseMs", String.valueOf(webToPauseMs),
                        "noBrowserMs", String.valueOf(coldMs), "noBrowserShown", coldShown,
                        "warmReadyMs", String.valueOf(warmReadyMs), "rewarmMs", String.valueOf(rewarmMs),
                        "heapWarmMb", String.valueOf(heapWarm), "heapNoneMb", String.valueOf(heapNone),
                        "chromiumRssWarmMb", String.valueOf(rssWarm), "chromiumRssNoneMb", String.valueOf(rssNone),
                        "fpsHidden", fmt(fpsHidden), "fpsNone", fmt(fpsNone),
                        "maxLive", String.valueOf(maxLive), "server", String.valueOf(mc.getSingleplayerServer() != null),
                        "pass", String.valueOf(pass));
                go(Step.DONE);
                return true;
            }
            case DONE -> {
                return true;
            }
        }
        return false;
    }

    private static long heapMb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) >> 20;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
