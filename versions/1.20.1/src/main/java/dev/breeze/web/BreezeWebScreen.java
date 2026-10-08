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
 * <p>Lifecycle: the browser is created in init and closed in removed, which
 * Minecraft calls on every exit path, including other screens opening on top.
 * Coming back (a parent returning here) runs init again and opens a fresh
 * browser on the route the page last reported.
 */
public final class BreezeWebScreen extends BreezeScreen {

    private static final long ESCAPE_FAILSAFE_NANOS = 500_000_000L;
    private static final int TITLE_BACKDROP = 0xFF0B0F18;

    private final boolean ingame;
    private BreezeBrowser browser;
    private final ConcurrentLinkedQueue<Runnable> afterAnswer = new ConcurrentLinkedQueue<>();
    private volatile long escapeDeadline;
    private volatile int escapeAnswer; // 0 none, 1 handled, 2 not handled
    /** Escapes in a row the page did not answer. Reset by any answer. */
    private int unansweredEscapes;

    public BreezeWebScreen(boolean ingame) {
        super(Component.literal("Breeze"));
        this.ingame = ingame;
    }

    boolean ingame() {
        return ingame;
    }

    /** Whether the page has painted at least one frame. Used by the dev self-test. */
    public boolean painted() {
        BreezeBrowser b = browser;
        return b != null && !b.isClosed() && b.painted();
    }

    @Override
    protected void init() {
        if (browser == null || browser.isClosed()) {
            browser = BreezeBrowser.open(Handlers.build(this), width, height);
            if (browser == null) {
                fallBack(UiState.takeOpenProblem("the embedded browser is not available"));
                return;
            }
            if (!ingame) UiState.titleOpened();
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
    }

    void onEscapeAck(boolean handled) {
        escapeAnswer = handled ? 1 : 2;
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
                fallBack("the page did not answer Escape twice");
            }
        }
    }

    /** Escape at the page's root: back to the game in a world, nothing on the title menu. */
    private void leaveFromRoot() {
        if (ingame) onClose();
    }

    @Override
    protected boolean onKeyPressed(int key, int scanCode, int modifiers) {
        dev.breeze.devtest.AutoTest.key(key);
        if (key == InputConstants.KEY_ESCAPE) {
            if (browser == null) {
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
        if (!ingame && (browser == null || !browser.painted())) g.fill(0, 0, width, height, TITLE_BACKDROP);
        if (browser != null) browser.render(g, width, height);
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
        if (browser != null) {
            browser.close();
            browser = null;
        }
        afterAnswer.clear();
        escapeDeadline = 0;
    }
}
