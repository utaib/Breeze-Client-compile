package dev.breeze.web;

import dev.breeze.compat.BreezeScreen;
import dev.breeze.BreezeClient;
import dev.breeze.menu.BreezeMenuScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The Breeze interface as a full-screen Minecraft screen.
 *
 * <p>Two contexts. {@code title} stands in for Minecraft's title screen: it
 * cannot be closed (Escape at the root does nothing, exactly like vanilla), and
 * Singleplayer and Multiplayer open Minecraft's own screens with this one as
 * their parent, so Back returns here. {@code ingame} opens over a world: it
 * pauses singleplayer like the vanilla game menu, and Escape at the root
 * returns to the game.
 *
 * <p>Escape is a handshake. Java does not forward it to Chromium; it sends the
 * page a key.escape event, and the page answers ui.escapeAck saying whether it
 * used it (closed a dialog, went back a page). If the page does not answer
 * within 500 ms, because it failed to load or hung, Java acts on its own, so
 * the player always has a way out of a broken page.
 *
 * <p>A page that never starts is a failed open too. If the page has not
 * called the bridge {@link #PAGE_TIMEOUT_MS} after its browser was created,
 * the browser is closed and the open counts as failed (fallBack), so the
 * player gets a working screen and a fresh browser instead of a dark one.
 * Seen in run 37783649946 on 26.1.1: the first browser after Chromium's
 * first download painted nothing and its page never ran for over a minute,
 * while the next browser opened normally.
 *
 * <p>Lifecycle: the screen borrows the one interface browser in init and
 * gives it back in removed, which Minecraft calls on every exit path,
 * including other screens opening on top ({@link WarmBrowser}). Each init is
 * a new visit: the page draws a fresh menu on the route it last reported and
 * calls ui.shown. Until then this screen draws its own placeholder (the
 * menu's panel and a spinner, never a grey screen), and in a world, if the
 * page has not shown within {@link #FALLBACK_MS}, Breeze's native menu opens
 * instead while the browser goes on loading for the next open.
 */
public final class BreezeWebScreen extends BreezeScreen {

    private static final long ESCAPE_FAILSAFE_NANOS = 500_000_000L;
    /** How long a page may take to call the bridge (it does so as it mounts, about 3 s in CI's software rendering). */
    static final long PAGE_TIMEOUT_MS = 12_000L;
    private static final int TITLE_BACKDROP = 0xFF0B0F18;
    /** In a world: how long the placeholder may show before the native menu opens instead. */
    static final long FALLBACK_MS = Long.getLong("breeze.menu.fallbackMs", 1_000L);
    /** The same for a page already loaded in the kept browser. */
    static final long KEPT_FALLBACK_MS = Long.getLong("breeze.menu.keptFallbackMs", 3_000L);

    /** A screen no one sees, whose handlers answer a hidden browser's page (WarmBrowser). */
    private final boolean standby;
    /** When init began this visit, and when the page's picture was first drawn in it (self-test, log). */
    private long openedAt;
    private long drawnAt;
    /** The key or button that opened this screen, for the log line (BreezeUi.open sets it). */
    private long requestedAt;

    private final boolean ingame;
    private BreezeBrowser browser;
    private final ConcurrentLinkedQueue<Runnable> afterAnswer = new ConcurrentLinkedQueue<>();
    private volatile long escapeDeadline;
    private volatile int escapeAnswer; // 0 none, 1 handled, 2 not handled
    /** This screen could not open its browser and is handing over to another. */
    private boolean fellBack;
    /** Escapes in a row the page did not answer. Reset by any answer. */
    private int unansweredEscapes;
    /** The browser failed this visit (its page never showed): closed rather than kept for the next open. */
    private boolean broken;
    /** The page in this screen's browser has answered (and the open counted as a success). */
    private boolean pageConfirmed;

    public BreezeWebScreen(boolean ingame) {
        this(ingame, false);
    }

    private BreezeWebScreen(boolean ingame, boolean standby) {
        super(Component.literal("Breeze"));
        this.ingame = ingame;
        this.standby = standby;
    }

    /** The handlers' screen for a hidden browser: never shown, refuses nothing it is never asked. */
    static BreezeWebScreen standby(boolean ingame) {
        return new BreezeWebScreen(ingame, true);
    }

    boolean standby() {
        return standby;
    }

    /** When the player asked for this screen (nanoTime), so the log can say how long it took. */
    public BreezeWebScreen requestedAt(long nanos) {
        this.requestedAt = nanos;
        return this;
    }

    /** Milliseconds from the request (or init) to the page's picture drawn; -1 until it is. */
    public long drawnMillis() {
        if (drawnAt == 0) return -1;
        return (drawnAt - (requestedAt != 0 ? requestedAt : openedAt)) / 1_000_000L;
    }

    /** Self-test: Escape as the player presses it in this screen. */
    public void testEscape() {
        onKeyPressed(InputConstants.KEY_ESCAPE, 0, 0);
    }

    /** The page has drawn this visit (ui.shown). */
    void pageShown() {
        if (standby) return;
        BreezeBrowser b = browser;
        if (b != null) b.markShown();
    }

    boolean ingame() {
        return ingame;
    }

    /** Whether the page has answered the bridge (its script runs). Used by the dev self-test. */
    public boolean pageConfirmed() {
        return pageConfirmed;
    }

    /** Whether the page has drawn this visit and its picture is on screen. Used by the dev self-test. */
    public boolean painted() {
        BreezeBrowser b = browser;
        return b != null && !b.isClosed() && b.painted() && b.visitShown();
    }

    @Override
    protected void init() {
        // Minecraft can call init again (a resize) before the fallback has
        // switched screens; one failed screen is one failed open, not two
        // (the web-open probe, run 37776057267, counted two in one second).
        if (fellBack) return;
        if (browser == null || browser.isClosed()) {
            openedAt = System.nanoTime();
            drawnAt = 0;
            browser = WarmBrowser.take(this, width, height);
            if (browser == null) {
                fallBack(UiState.takeOpenProblem("the embedded browser is not available"));
                return;
            }
            pageConfirmed = false;
        } else {
            browser.resize(width, height);
        }
    }

    /**
     * No browser: show something that works rather than an empty screen.
     *
     * Deferred to the next tick, never done here. This runs from init(),
     * which Minecraft calls from inside setScreen, and Minecraft::execute on
     * the client thread runs at once, so switching here would re-enter
     * setScreen halfway through the one that is showing this screen.
     */
    private void fallBack(String why) {
        fellBack = true;
        Minecraft mc = Minecraft.getInstance();
        if (ingame) {
            BreezeClient.LOGGER.warn("[Breeze] web menu unavailable ({}); using the native menu", why);
            afterAnswer(() -> dev.breeze.compat.ActiveScreen.set(mc, new BreezeMenuScreen()));
        } else {
            // Minecraft's title screen for now; WebInit.tick tries the web menu
            // again shortly, and only after MAX_FAILED_OPENS failures in a row
            // does the title stay Minecraft's for the session.
            int attempt = UiState.titleOpenFailed();
            if (UiState.gaveUp()) {
                BreezeClient.LOGGER.warn("[Breeze] web menu could not open ({}), attempt {} of {}; Minecraft's title screen stays for this session",
                        why, attempt, UiState.MAX_FAILED_OPENS);
            } else {
                BreezeClient.LOGGER.warn("[Breeze] web menu could not open ({}), attempt {} of {}; showing Minecraft's title screen and trying again in 3 s",
                        why, attempt, UiState.MAX_FAILED_OPENS);
            }
            afterAnswer(() -> dev.breeze.compat.ActiveScreen.set(mc, new TitleScreen()));
        }
    }

    /** Run after the current bridge answer is sent (screen changes, quitting). */
    void afterAnswer(Runnable r) {
        afterAnswer.add(r);
        // At the start of the next frame (Minecraft's task queue runs before
        // it draws, a safe point to change screens), not at the next game
        // tick: waiting for the tick added up to 50 ms to every Escape, Back
        // and screen change from the menu (testers, 2026-11-12: the menus
        // have a little input delay, the game menu does not pop instantly).
        Minecraft.getInstance().execute(this::drainAfterAnswer);
    }

    void onEscapeAck(boolean handled) {
        escapeAnswer = handled ? 1 : 2;
        Minecraft.getInstance().execute(this::settleEscape);
    }

    private void drainAfterAnswer() {
        Runnable r;
        while ((r = afterAnswer.poll()) != null) {
            try {
                r.run();
            } catch (Throwable t) {
                BreezeClient.LOGGER.warn("[Breeze] menu action failed: {}", t.toString());
            }
        }
    }

    @Override
    public void tick() {
        drainAfterAnswer();
        watchPage();
        settleEscape();
    }

    /** The page's answer to Escape, or the fail-safe once it is late. */
    private void settleEscape() {
        // Only for the screen that is showing (26.2 moved the screen field;
        // ActiveScreen reads it on every version).
        if (dev.breeze.compat.ActiveScreen.get(Minecraft.getInstance()) != this) return;
        long deadline = escapeDeadline;
        if (deadline == 0) return;
        int answer = escapeAnswer;
        if (answer == 1) {
            escapeDeadline = 0;
            unansweredEscapes = 0;
        } else if (answer == 2) {
            escapeDeadline = 0;
            unansweredEscapes = 0;
            leaveFromRoot();
        } else if (System.nanoTime() > deadline) {
            escapeDeadline = 0;
            unansweredEscapes++;
            BreezeClient.LOGGER.warn("[Breeze] the menu did not answer Escape ({} in a row)", unansweredEscapes);
            if (ingame) {
                onClose();
            } else if (unansweredEscapes >= 2) {
                // One unanswered press may just be the page still loading; two
                // in a row means it is not working, and the player needs a
                // title screen that is.
                broken = true;
                fallBack("the page did not answer Escape twice");
            }
        }
    }

    /**
     * An open succeeds when the page answers, not when the browser is
     * created: a browser whose page never runs is as broken as no browser.
     */
    private void watchPage() {
        BreezeBrowser b = browser;
        if (fellBack || b == null || b.isClosed() || pageConfirmed) return;
        if (b.visitShown()) {
            pageConfirmed = true;
            if (!ingame) UiState.titleOpened();
            return;
        }
        // A page still loading gets FALLBACK_MS; one already loaded (the kept
        // browser) is only drawing a fresh menu and gets KEPT_FALLBACK_MS, so
        // a busy moment does not swap it for the native menu just before it
        // shows (run 44911947345: 1.5 s on a slow build machine).
        if (ingame && b.visitAgeMillis() >= (b.pageAnswered() ? KEPT_FALLBACK_MS : FALLBACK_MS)) {
            // The page is not ready yet (Chromium still starting on a busy
            // computer): the native menu now rather than a placeholder, and
            // the browser goes on loading for the next open.
            fellBack = true;
            BreezeClient.LOGGER.info("[Breeze] the menu's page was not ready {} ms after opening ({}); showing the native menu",
                    b.visitAgeMillis(), b.pageAnswered() ? "drawing" : "still loading");
            Minecraft mc = Minecraft.getInstance();
            afterAnswer(() -> dev.breeze.compat.ActiveScreen.set(mc, new BreezeMenuScreen()));
            return;
        }
        // A page that has loaded gets the time from this visit; one still
        // loading, from its browser's creation.
        long age = b.pageAnswered() ? b.visitAgeMillis() : b.ageMillis();
        if (age < PAGE_TIMEOUT_MS) return;
        broken = true;
        fallBack("the page did not start within " + (age / 1000) + " s; Chromium "
                + (b.painted() ? "painted it but its script never reached the bridge" : "painted nothing"));
    }

    /** Escape at the page's root: back to the game in a world, nothing on the title menu. */
    private void leaveFromRoot() {
        if (ingame) onClose();
    }

    @Override
    protected boolean onKeyPressed(int key, int scanCode, int modifiers) {
        dev.breeze.devtest.AutoTest.key(key);
        // A command key is waiting for its key: Ctrl, Shift or Alt alone keep
        // it waiting (they go with the key), the next other key is taken
        // with the ones held, and the page does not see it.
        if (dev.breeze.commands.CommandKeyRunner.capturing() != null) {
            if (dev.breeze.commands.CommandKeyRunner.modifierKey(key)) return true;
            String[] taken = dev.breeze.commands.CommandKeyRunner.takeKey(Minecraft.getInstance(), key, scanCode);
            if (browser != null) {
                com.google.gson.JsonObject changed = new com.google.gson.JsonObject();
                changed.addProperty("id", taken[0]);
                changed.addProperty("cancelled", key == InputConstants.KEY_ESCAPE);
                if (taken[1] != null) changed.addProperty("refused", taken[1]);
                browser.emit("commandKeys.changed", changed);
            }
            return true;
        }
        // The Keys page asked for a key: this press is the new binding (or,
        // Escape, a change of mind), and the page does not see it.
        if (dev.breeze.keybind.KeybindManager.capturing() != null) {
            String id = dev.breeze.keybind.KeybindManager.takeKey(Minecraft.getInstance(), key, scanCode);
            if (browser != null) {
                com.google.gson.JsonObject changed = new com.google.gson.JsonObject();
                changed.addProperty("id", id);
                changed.addProperty("cancelled", key == InputConstants.KEY_ESCAPE);
                browser.emit("keys.changed", changed);
            }
            return true;
        }
        if (key == InputConstants.KEY_ESCAPE) {
            if (browser == null || !browser.visitShown()) {
                if (ingame) onClose();
                return true;
            }
            if (escapeDeadline == 0) {
                escapeAnswer = 0;
                escapeDeadline = System.nanoTime() + ESCAPE_FAILSAFE_NANOS;
                browser.emit("key.escape", null);
            }
            return true;
        }
        if (browser != null) {
            browser.keyPressed(key, scanCode, modifiers);
            // Chromium presses a focused button on Enter's character event,
            // not its key-down, and Minecraft sends no character for Enter.
            if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) browser.charTyped('\r', modifiers);
        }
        return true;
    }

    @Override
    protected boolean onKeyReleased(int key, int scanCode, int modifiers) {
        if (key != InputConstants.KEY_ESCAPE && browser != null) browser.keyReleased(key, scanCode, modifiers);
        return true;
    }

    @Override
    protected boolean onCharTyped(int c, int modifiers) {
        if (browser != null && Character.isBmpCodePoint(c)) browser.charTyped((char) c, modifiers);
        return true;
    }

    @Override
    protected void onMouseMoved(double x, double y) {
        if (browser != null) browser.mouseMoved(x, y);
    }

    @Override
    protected boolean onMouseClicked(double x, double y, int button) {
        if (browser != null) browser.mousePressed(x, y, button);
        return true;
    }

    @Override
    protected boolean onMouseReleased(double x, double y, int button) {
        if (browser != null) browser.mouseReleased(x, y, button);
        return true;
    }

    @Override
    protected boolean onMouseDragged(double x, double y, int button, double dx, double dy) {
        if (browser != null) browser.mouseMoved(x, y);
        return true;
    }

    @Override
    protected boolean onMouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (browser != null) browser.mouseScrolled(x, y, scrollY);
        return true;
    }

    @Override
    protected void onResized(int w, int h) {
        if (browser != null) browser.resize(w, h);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Queued screen changes run from tick(), never from here: switching
        // screens halfway through a frame leaves Minecraft and Fabric API
        // drawing a screen that is no longer current. (In the first real run,
        // Singleplayer on a game with no worlds crashed the client this way:
        // Fabric's after-render hook got a null screen.)
        // Until the page paints its first frame, the title context shows the
        // interface's own background colour rather than a black window.
        BreezeBrowser b = browser;
        if (b != null && b.visitShown() && b.painted()) {
            // Nothing drawn under the page: up to 1.21.4 the page is drawn
            // straight away while GuiGraphics fills wait for the end of the
            // frame (1.21.2 on), so a fill here covered the page (the title
            // menu showed only its backdrop on 1.21.3 and 1.21.4).
            b.render(g, width, height);
            if (drawnAt == 0) {
                drawnAt = System.nanoTime();
                long ms = drawnMillis();
                BreezeClient.LOGGER.info("[Breeze] menu drawn {} ms after it was asked for ({}, {})", ms,
                        ingame ? "in a world" : "title menu", b.pageAnswered() && b.ageMillis() - ms > 50 ? "browser kept loaded" : "new browser");
            }
            return;
        }
        Placeholder.draw(g, font, width, height, ingame, TITLE_BACKDROP);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return ingame;
    }

    @Override
    public void onClose() {
        if (!ingame) return; // the title menu is only left by choosing something
        dev.breeze.compat.ActiveScreen.set(Minecraft.getInstance(), null);
    }

    @Override
    public void removed() {
        dev.breeze.keybind.KeybindManager.cancelCapture();
        if (browser != null) {
            if (broken) browser.close();
            else WarmBrowser.giveBack(browser);
            browser = null;
        }
        afterAnswer.clear();
        escapeDeadline = 0;
    }
}
