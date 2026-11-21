package dev.breeze.web;

import dev.breeze.BreezeClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;

/**
 * The one interface browser, kept loaded between opens of the Breeze menu.
 *
 * <p>Before 2.30.0 every open of the menu created a Chromium browser and
 * loaded the page, and every close destroyed it. On the owner's laptop
 * (i5-1135G7, four cores shared with the singleplayer server) the page
 * answered 6.6 s after Right Shift in a world, against 0.5 s at the title
 * screen, and the player saw a grey screen meanwhile (2026-11-20). Now a
 * screen borrows this browser ({@link #take}) and gives it back when it closes
 * ({@link #giveBack}); the page stays loaded and only draws a fresh menu for
 * each visit.
 *
 * <p>When there is none (the first open after the game starts in a world,
 * or after it was freed), one is made in the background a few seconds after
 * the player is in a world with no screen open, or when the game menu
 * (Escape) opens, so it is ready before Right Shift.
 *
 * <p>Memory: a hidden browser costs a Chromium render process outside the
 * Java heap, its last picture on the graphics card, and nothing per frame
 * (the page draws nothing while hidden, so Chromium paints nothing). It is
 * freed after {@link #IDLE_MS} unused, when the Java heap runs short, and when
 * the player switches to the classic menu, and not kept when the computer
 * has under 400 MB free. There is never more than one. Measured on the build
 * machines (run 44908114407): Chromium's processes 561 MB with it hidden,
 * 406 MB without, the Java heap unchanged.
 *
 * <p>Only the client thread calls this class.
 */
public final class WarmBrowser {

    /** A hidden browser not used for this long is closed (made again when the game menu opens). */
    static final long IDLE_MS = Long.getLong("breeze.menu.idleMs", 20L * 60_000L);
    /** In a world this long with no screen open before a browser is made in the background. */
    private static final long SETTLE_MS = 3_000L;
    /** A background browser whose page has not started by then is closed and counted as a failure. */
    private static final long STANDBY_TIMEOUT_MS = 40_000L;
    /** Background browsers that failed in a row before giving up for the session. */
    private static final int MAX_FAILURES = 3;
    /** The Java heap free below this share of its maximum, twice 5 s apart: memory is short. */
    private static final double SHORT_HEAP = 0.10;

    private static BreezeBrowser warm;
    private static long hiddenSince;
    private static boolean freedForIdle;
    private static int failures;
    private static long worldSince;
    private static long shortSeenAt;
    private static long lastHeapCheck;

    private WarmBrowser() {}

    /**
     * The browser for a screen that is opening: the warm one, pointed at the
     * screen, or a new one. Null when none can be made (the reason is in
     * {@link UiState#takeOpenProblem}).
     */
    static BreezeBrowser take(BreezeWebScreen screen, int guiWidth, int guiHeight) {
        BreezeBrowser b = warm;
        warm = null;
        if (b != null && !b.isClosed()) {
            b.bind(Handlers.build(screen));
            b.resize(guiWidth, guiHeight);
            b.visit();
            BreezeClient.LOGGER.info("[Breeze] interface browser reused ({} the page {})",
                    b.pageAnswered() ? "loaded" : "still loading", b.pageAnswered() ? "earlier" : "now");
            return b;
        }
        freedForIdle = false;
        return BreezeBrowser.open(Handlers.build(screen), guiWidth, guiHeight);
    }

    /** A screen closed: keep its browser hidden for the next open, or close it. */
    static void giveBack(BreezeBrowser b) {
        if (b == null || b.isClosed()) return;
        if (!wanted() || memoryShort(true)) {
            b.close();
            return;
        }
        if (warm != null && warm != b) warm.close();
        b.hide();
        b.bind(Handlers.build(BreezeWebScreen.standby(Minecraft.getInstance().level != null)));
        warm = b;
        hiddenSince = System.currentTimeMillis();
    }

    /** Each client tick: free the hidden browser when it should go, make one when it is time. */
    public static void tick(Minecraft mc) {
        long now = System.currentTimeMillis();
        boolean inWorld = mc.level != null && mc.player != null;
        if (!inWorld) worldSince = 0;
        else if (worldSince == 0) worldSince = now;
        BreezeBrowser b = warm;
        if (b != null) {
            if (b.isClosed()) {
                warm = null;
            } else if (!wanted()) {
                free("the classic menu was chosen");
            } else if (now - hiddenSince > IDLE_MS) {
                freedForIdle = true;
                free("unused for " + (IDLE_MS / 60_000L) + " min");
            } else if (memoryShort(false)) {
                free("memory is short (Java heap " + heapUsedMb() + " of " + (Runtime.getRuntime().maxMemory() >> 20)
                        + " MB, " + systemFreeMb() + " MB free on the computer)");
            } else if (!b.pageAnswered() && b.ageMillis() > STANDBY_TIMEOUT_MS) {
                failures++;
                free("its page did not start within " + (STANDBY_TIMEOUT_MS / 1000) + " s (" + failures + " of " + MAX_FAILURES + ")");
            }
            return;
        }
        if (!inWorld || BrowserCount.live() > 0 || failures >= MAX_FAILURES || !wanted()) return;
        Screen screen = dev.breeze.compat.ActiveScreen.get(mc);
        boolean pause = screen instanceof PauseScreen;
        boolean playing = screen == null && now - worldSince > SETTLE_MS && !freedForIdle;
        if (!pause && !playing) return;
        if (dev.breeze.compat.ActiveScreen.overlay(mc) != null || memoryShort(true)) return;
        int w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
        if (w <= 0 || h <= 0) return;
        BreezeBrowser made = BreezeBrowser.open(Handlers.build(BreezeWebScreen.standby(true)), w, h);
        if (made == null) {
            failures++;
            BreezeClient.LOGGER.warn("[Breeze] the menu's browser could not be made ahead of time ({}), {} of {}",
                    UiState.takeOpenProblem("unknown"), failures, MAX_FAILURES);
            return;
        }
        made.hide();
        warm = made;
        hiddenSince = now;
        freedForIdle = false;
        BreezeClient.LOGGER.info("[Breeze] interface browser made ahead of time, {} (heap {} of {} MB)",
                pause ? "the game menu opened" : "in a world", heapUsedMb(), Runtime.getRuntime().maxMemory() >> 20);
    }

    /** The page of a background browser answered: it can be counted as working. */
    static void standbyAnswered() {
        failures = 0;
    }

    private static void free(String why) {
        BreezeBrowser b = warm;
        warm = null;
        if (b == null) return;
        BreezeClient.LOGGER.info("[Breeze] interface browser freed: {}", why);
        b.close();
    }

    /** Self-test: closes the hidden browser, as the idle limit does. */
    public static void freeForTest() {
        freedForIdle = true;
        free("self-test");
    }

    /** Self-test: a hidden browser may be made again (as the game menu allows). */
    public static void allowAgain() {
        freedForIdle = false;
    }

    /** Whether a hidden browser is waiting (self-test and log). */
    public static boolean ready() {
        BreezeBrowser b = warm;
        return b != null && !b.isClosed() && b.pageAnswered();
    }

    /** Whether one is kept at all (it may still be loading). */
    public static boolean held() {
        BreezeBrowser b = warm;
        return b != null && !b.isClosed();
    }

    /** The player wants the web menu and it can open. */
    private static boolean wanted() {
        return dev.breeze.ui.BreezeUi.webAvailable() && dev.breeze.ui.Theme.uiMode != dev.breeze.ui.Theme.UiMode.NATIVE;
    }

    /**
     * Whether the Java heap is short. A single reading can be garbage not yet
     * collected, so a background check needs two short readings 5 s apart;
     * {@code now} asks for this reading alone (before making a browser).
     */
    private static boolean memoryShort(boolean now) {
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory();
        long free = max - (rt.totalMemory() - rt.freeMemory());
        boolean shortNow = free < max * SHORT_HEAP || systemFreeMb() < SHORT_SYSTEM_MB;
        if (now) return shortNow;
        long t = System.currentTimeMillis();
        if (t - lastHeapCheck < 5_000L) return false;
        lastHeapCheck = t;
        if (!shortNow) {
            shortSeenAt = 0;
            return false;
        }
        if (shortSeenAt == 0) {
            shortSeenAt = t;
            return false;
        }
        return true;
    }

    /** The computer's free memory below this (MB): a hidden browser (about 150 MB of Chromium's) is not kept. */
    private static final long SHORT_SYSTEM_MB = Long.getLong("breeze.menu.minFreeMb", 400L);

    /** The computer's free physical memory in MB, or a large number when the runtime cannot say. */
    static long systemFreeMb() {
        // Linux: MemAvailable counts the file cache the system gives back;
        // the runtime's free figure there does not, and reads low on any
        // machine that has been busy.
        try {
            java.nio.file.Path info = java.nio.file.Path.of("/proc/meminfo");
            if (java.nio.file.Files.isReadable(info)) {
                for (String line : java.nio.file.Files.readAllLines(info)) {
                    if (line.startsWith("MemAvailable:")) return Long.parseLong(line.replaceAll("[^0-9]", "")) >> 10;
                }
            }
        } catch (Throwable ignored) {}
        try {
            java.lang.management.OperatingSystemMXBean os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
                long free = sun.getFreePhysicalMemorySize();
                if (free > 0) return free >> 20;
            }
        } catch (Throwable ignored) {}
        return Long.MAX_VALUE;
    }

    static long heapUsedMb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) >> 20;
    }
}
