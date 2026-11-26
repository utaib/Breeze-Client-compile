package dev.breeze.web;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import com.google.gson.JsonElement;
import dev.breeze.BreezeClient;
import dev.breeze.bridge.BridgeError;
import dev.breeze.bridge.Events;
import dev.breeze.bridge.PageOrigin;
import dev.breeze.bridge.Router;
import dev.breeze.compat.Buttons;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.cef.browser.CefBrowser;
import org.cef.callback.CefQueryCallback;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One MCEF browser showing the Breeze interface.
 *
 * Since 2.30.0 it outlives the screen that shows it: a screen borrows it in
 * init and gives it back in removed ({@link WarmBrowser}), so the next open
 * draws a page that is already loaded instead of starting Chromium and the
 * page again (6.6 s on a four-core laptop in a world, 2026-11-20). Each open
 * is a visit: the bridge is pointed at the showing screen's handlers
 * ({@link #bind}), the page is told to draw afresh (app.visit) and says when
 * it has (ui.shown). Between visits the page draws nothing (app.hide), so
 * Chromium has nothing to repaint. Closing answers every request still
 * pending, detaches from the bridge, and closes the Chromium browser, which
 * releases its render process and its GPU texture. {@link #live()} counts
 * open instances (never more than one).
 *
 * Coordinates: the browser is sized in real framebuffer pixels (GUI size times
 * GUI scale, as MCEF's own example does), so the interface stays sharp at any
 * GUI scale and window size. Input arrives in GUI units and is scaled the same
 * way; the texture is drawn back over the GUI-unit rectangle.
 */
public final class BreezeBrowser {


    private final MCEFBrowser browser;
    private volatile Router router;
    /** Visits begun (a screen showing this browser) and the newest the page has drawn. */
    private int visits;
    private volatile int shownVisit = -1;
    private volatile long visitStartedAt = System.nanoTime();
    private volatile long shownAt;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final long openedAt = System.nanoTime();
    /** The page has made a bridge call: its script ran and found the bridge. */
    private volatile boolean answered;
    private int pixelWidth;
    private int pixelHeight;
    /** Browser pixels per GUI unit (the GUI scale, less on very large windows; see fit). */
    private volatile double pxPerGui;

    /**
     * Most pixels the page is drawn at. Every repaint is copied from Chromium
     * into the game, so a 4K window (8.3 million pixels) cost four times what
     * 1080p does for a page that reads the same at either size. Larger windows
     * get the page at this size, scaled up by the GPU.
     */
    private static final long MAX_PIXELS = 1920L * 1200L;

    /** The browser's size in pixels for a GUI size, and its pixels per GUI unit. */
    static double[] fit(int guiWidth, int guiHeight, double guiScale) {
        double per = guiScale;
        double pixels = guiWidth * per * guiHeight * per;
        if (pixels > MAX_PIXELS) per *= Math.sqrt(MAX_PIXELS / pixels);
        return new double[]{Math.max(1, Math.round(guiWidth * per)), Math.max(1, Math.round(guiHeight * per)), per};
    }

    private BreezeBrowser(MCEFBrowser browser, Router router, int w, int h, double pxPerGui) {
        this.browser = browser;
        this.router = router;
        this.pixelWidth = w;
        this.pixelHeight = h;
        this.pxPerGui = pxPerGui;
    }

    /** Self-test only (-Dbreeze.autotest.failWebOpens=N): the first N opens fail, to test the recovery. */
    private static final int FORCED_FAILURES = Integer.getInteger("breeze.autotest.failWebOpens", 0);
    private static int forcedFailures;
    /** Self-test only (-Dbreeze.autotest.stallWebPages=N): the first N browsers open a page that never starts, to test the page watchdog. */
    private static final int STALLED_PAGES = Integer.getInteger("breeze.autotest.stallWebPages", 0);
    private static int stalledPages;

    static BreezeBrowser open(Router router, int guiWidth, int guiHeight) {
        if (!WebInit.available()) {
            UiState.openProblem("Chromium is not ready, state " + WebInit.state());
            return null;
        }
        if (!BreezeWeb.install()) {
            UiState.openProblem("the page origin or the bridge could not be set up, see the line before");
            return null;
        }
        if (forcedFailures < FORCED_FAILURES) {
            forcedFailures++;
            UiState.openProblem("self-test: forced failure " + forcedFailures + " of " + FORCED_FAILURES);
            router.close();
            return null;
        }
        try {
            double[] fit = fit(guiWidth, guiHeight, Minecraft.getInstance().getWindow().getGuiScale());
            int w = (int) fit[0];
            int h = (int) fit[1];
            String url = PageOrigin.INDEX;
            if (stalledPages < STALLED_PAGES) {
                stalledPages++;
                url = "about:blank";
                BreezeClient.LOGGER.info("[Breeze] self-test: browser {} of {} opens a page that never starts", stalledPages, STALLED_PAGES);
            }
            long t0 = System.nanoTime();
            MCEFBrowser b = MCEF.createBrowser(url, true, w, h);
            long createMs = (System.nanoTime() - t0) / 1_000_000L;
            b.resize(w, h);
            // Chromium paints an off-screen page 30 times a second unless told
            // otherwise, so a click showed up to 33 ms late on top of the
            // game's own frame. 60 matches what players see elsewhere.
            // By reflection: every MCEF and Rinku build has JCEF's method, but
            // a build without it must still open the menu.
            try {
                b.getClass().getMethod("setWindowlessFrameRate", int.class).invoke(b, 60);
            } catch (Throwable t) {
                BreezeClient.LOGGER.info("[Breeze] browser frame rate left at Chromium's default: {}", t.toString());
            }
            b.setFocus(true);
            BreezeBrowser session = new BreezeBrowser(b, router, w, h, fit[2]);
            BreezeWeb.attach(session);
            BrowserCount.LIVE.incrementAndGet();
            BreezeClient.LOGGER.info("[Breeze] interface browser opened at {}x{} px ({} live, created in {} ms on the render thread)",
                    w, h, BrowserCount.live(), createMs);
            return session;
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] interface browser could not be created: {}", t.toString());
            UiState.openProblem("Chromium could not create the browser: " + t);
            router.close();
            return null;
        }
    }

    /** Points the bridge at another screen's handlers; the old ones answer what they still owe and stop. */
    void bind(Router next) {
        Router old = router;
        router = next;
        if (old != null && old != next) old.close();
    }

    /**
     * A screen shows this browser again: the page draws a fresh menu for it
     * (app.visit) and the screen draws its placeholder until ui.shown.
     */
    void visit() {
        if (closed.get()) return;
        visits++;
        visitStartedAt = System.nanoTime();
        browser.setFocus(true);
        focusedOnPaint = false;
        emit("app.visit", null);
    }

    /** The screen showing it closed: the page removes its menu and the browser waits. */
    void hide() {
        if (closed.get()) return;
        visits++;
        browser.setFocus(false);
        emit("app.hide", null);
    }

    /** The page called ui.shown: the visit begun last is on screen. */
    void markShown() {
        if (shownVisit != visits) {
            shownVisit = visits;
            shownAt = System.nanoTime();
        }
    }

    /** Whether the page has drawn the current visit (its picture may be shown). */
    boolean visitShown() {
        return !closed.get() && shownVisit == visits;
    }

    /** Milliseconds since the current visit began (or the browser was created). */
    long visitAgeMillis() {
        return (System.nanoTime() - visitStartedAt) / 1_000_000L;
    }

    public static int live() {
        return BrowserCount.live();
    }

    boolean owns(CefBrowser b) {
        return !closed.get() && b == browser;
    }

    void dispatch(long queryId, String request, CefQueryCallback callback) {
        if (!answered) {
            answered = true;
            BreezeClient.LOGGER.info("[Breeze] the interface page answered {} ms after its browser opened", ageMillis());
        }
        Router r = router;
        if (r == null) {
            callback.failure(BridgeError.CANCELLED.code(), "The Breeze menu is not showing.");
            return;
        }
        r.dispatch(queryId, request, new Router.Responder() {
            @Override
            public void success(String json) {
                callback.success(json);
            }

            @Override
            public void failure(int code, String message) {
                callback.failure(code, message);
            }
        });
    }

    void cancel(long queryId) {
        Router r = router;
        if (r != null) r.cancel(queryId);
    }

    /** Push an event to the page. Safe from any thread; dropped once closed. */
    public void emit(String type, JsonElement payload) {
        if (closed.get()) return;
        try {
            browser.executeJavaScript(Events.script(type, payload), PageOrigin.INDEX, 0);
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] event {} not delivered: {}", type, t.toString());
        }
    }

    // ── input, in GUI units ─────────────────────────────────────────────────

    private int px(double gui) {
        return (int) Math.round(gui * pxPerGui);
    }

    void mouseMoved(double x, double y) {
        if (!closed.get()) browser.sendMouseMove(px(x), px(y));
    }

    // Buttons arrive numbered the Breeze way (0 is left). The browser takes
    // Minecraft's own numbers: Rinku 3.0.5 on 26.3 maps them itself, and
    // there SDL's left button is 1, so a 0 was dropped as no button.
    void mousePressed(double x, double y, int button) {
        if (closed.get()) return;
        browser.setFocus(true);
        browser.sendMousePress(px(x), px(y), Buttons.toGame(button));
    }

    void mouseReleased(double x, double y, int button) {
        if (closed.get()) return;
        browser.setFocus(true);
        browser.sendMouseRelease(px(x), px(y), Buttons.toGame(button));
    }

    void mouseScrolled(double x, double y, double amount) {
        if (!closed.get()) browser.sendMouseWheel(px(x), px(y), amount, 0);
    }

    void keyPressed(int key, int scanCode, int modifiers) {
        if (closed.get()) return;
        browser.setFocus(true);
        browser.sendKeyPress(key, scanCode, modifiers);
    }

    void keyReleased(int key, int scanCode, int modifiers) {
        if (closed.get()) return;
        browser.setFocus(true);
        browser.sendKeyRelease(key, scanCode, modifiers);
    }

    void charTyped(char c, int modifiers) {
        if (closed.get() || c == 0) return;
        browser.setFocus(true);
        browser.sendKeyTyped(c, modifiers);
    }

    void resize(int guiWidth, int guiHeight) {
        if (closed.get()) return;
        double[] fit = fit(guiWidth, guiHeight, Minecraft.getInstance().getWindow().getGuiScale());
        int w = (int) fit[0];
        int h = (int) fit[1];
        pxPerGui = fit[2];
        if (w == pixelWidth && h == pixelHeight) return;
        pixelWidth = w;
        pixelHeight = h;
        browser.resize(w, h);
    }

    /**
     * Whether the page has called the bridge yet. Until it has, nothing the
     * player sees on it works (BreezeWebScreen's page watchdog).
     */
    boolean pageAnswered() {
        return answered;
    }

    /** Milliseconds since this browser was created. */
    long ageMillis() {
        return (System.nanoTime() - openedAt) / 1_000_000L;
    }

    /** Whether focus has been given again since the page's first frame. */
    private boolean focusedOnPaint;

    /** True once Chromium has painted at least one frame. */
    boolean painted() {
        return BrowserFrame.painted(browser);
    }

    /**
     * Draw the page over the whole screen.
     *
     * CEF paints premultiplied alpha into a transparent surface, so the blend
     * is ONE, ONE_MINUS_SRC_ALPHA: the world shows through wherever the page
     * leaves it clear, with no dark fringe on soft edges.
     */
    void render(GuiGraphics g, int guiWidth, int guiHeight) {
        if (closed.get() || !painted()) return;
        if (!focusedOnPaint) {
            // Focus asked for at creation can arrive before Chromium has a
            // page to give it to; keys then go nowhere until the first click.
            focusedOnPaint = true;
            browser.setFocus(true);
        }
        BrowserFrame.draw(g, browser, guiWidth, guiHeight, pixelWidth, pixelHeight);
    }

    /** Idempotent, any thread. */
    void close() {
        if (!closed.compareAndSet(false, true)) return;
        BreezeWeb.detach(this);
        Router r = router;
        if (r != null) r.close();
        try {
            browser.close();
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] interface browser close failed: {}", t.toString());
        }
        int left = BrowserCount.LIVE.decrementAndGet();
        BreezeClient.LOGGER.info("[Breeze] interface browser closed ({} live)", left);
    }

    boolean isClosed() {
        return closed.get();
    }
}
