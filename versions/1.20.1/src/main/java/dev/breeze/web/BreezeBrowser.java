package dev.breeze.web;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import com.cinemamod.mcef.MCEFRenderer;
import com.google.gson.JsonElement;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.breeze.BreezeClient;
import dev.breeze.bridge.Events;
import dev.breeze.bridge.PageOrigin;
import dev.breeze.bridge.Router;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.cef.browser.CefBrowser;
import org.cef.callback.CefQueryCallback;
import org.joml.Matrix4f;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One MCEF browser showing the Breeze interface, owned by one screen.
 *
 * Its lifetime is exactly the screen's: created in init, closed in removed.
 * Closing answers every request still pending, detaches from the bridge,
 * and closes the Chromium browser, which releases its render process and its
 * GPU texture. {@link #live()} counts open instances so tests and the debug
 * log can confirm that repeated open and close leaves nothing behind.
 *
 * Coordinates: the browser is sized in real framebuffer pixels (GUI size times
 * GUI scale, as MCEF's own example does), so the interface stays sharp at any
 * GUI scale and window size. Input arrives in GUI units and is scaled the same
 * way; the texture is drawn back over the GUI-unit rectangle.
 */
public final class BreezeBrowser {

    private static final AtomicInteger LIVE = new AtomicInteger();

    private final MCEFBrowser browser;
    private final Router router;
    private final AtomicBoolean closed = new AtomicBoolean();
    private int pixelWidth;
    private int pixelHeight;

    private BreezeBrowser(MCEFBrowser browser, Router router, int w, int h) {
        this.browser = browser;
        this.router = router;
        this.pixelWidth = w;
        this.pixelHeight = h;
    }

    /** Opens a browser on the interface, or returns null if MCEF cannot. */
    static BreezeBrowser open(Router router, int guiWidth, int guiHeight) {
        if (!WebInit.available() || !BreezeWeb.install()) return null;
        try {
            double scale = Minecraft.getInstance().getWindow().getGuiScale();
            int w = Math.max(1, (int) Math.round(guiWidth * scale));
            int h = Math.max(1, (int) Math.round(guiHeight * scale));
            MCEFBrowser b = MCEF.createBrowser(PageOrigin.INDEX, true, w, h);
            b.resize(w, h);
            b.setFocus(true);
            BreezeBrowser session = new BreezeBrowser(b, router, w, h);
            BreezeWeb.attach(session);
            LIVE.incrementAndGet();
            BreezeClient.LOGGER.info("[Breeze] interface browser opened at {}x{} px ({} live)", w, h, LIVE.get());
            return session;
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] interface browser could not be created: {}", t.toString());
            router.close();
            return null;
        }
    }

    public static int live() {
        return LIVE.get();
    }

    boolean owns(CefBrowser b) {
        return !closed.get() && b == browser;
    }

    void dispatch(long queryId, String request, CefQueryCallback callback) {
        router.dispatch(queryId, request, new Router.Responder() {
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
        router.cancel(queryId);
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

    private static int px(double gui) {
        return (int) Math.round(gui * Minecraft.getInstance().getWindow().getGuiScale());
    }

    void mouseMoved(double x, double y) {
        if (!closed.get()) browser.sendMouseMove(px(x), px(y));
    }

    void mousePressed(double x, double y, int button) {
        if (closed.get()) return;
        browser.setFocus(true);
        browser.sendMousePress(px(x), px(y), button);
    }

    void mouseReleased(double x, double y, int button) {
        if (closed.get()) return;
        browser.setFocus(true);
        browser.sendMouseRelease(px(x), px(y), button);
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
        double scale = Minecraft.getInstance().getWindow().getGuiScale();
        int w = Math.max(1, (int) Math.round(guiWidth * scale));
        int h = Math.max(1, (int) Math.round(guiHeight * scale));
        if (w == pixelWidth && h == pixelHeight) return;
        pixelWidth = w;
        pixelHeight = h;
        browser.resize(w, h);
    }

    /** Whether focus has been given again since the page's first frame. */
    private boolean focusedOnPaint;

    /** True once Chromium has painted at least one frame. */
    boolean painted() {
        MCEFRenderer r = browser.getRenderer();
        return r != null && r.getTextureID() != 0;
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
        int texture = browser.getRenderer().getTextureID();
        Matrix4f pose = g.pose().last().pose();
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texture);
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        buffer.vertex(pose, 0, guiHeight, 0).uv(0f, 1f).color(255, 255, 255, 255).endVertex();
        buffer.vertex(pose, guiWidth, guiHeight, 0).uv(1f, 1f).color(255, 255, 255, 255).endVertex();
        buffer.vertex(pose, guiWidth, 0, 0).uv(1f, 0f).color(255, 255, 255, 255).endVertex();
        buffer.vertex(pose, 0, 0, 0).uv(0f, 0f).color(255, 255, 255, 255).endVertex();
        Tesselator.getInstance().end();
        RenderSystem.setShaderTexture(0, 0);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
    }

    /** Idempotent, any thread. */
    void close() {
        if (!closed.compareAndSet(false, true)) return;
        BreezeWeb.detach(this);
        router.close();
        try {
            browser.close();
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] interface browser close failed: {}", t.toString());
        }
        int left = LIVE.decrementAndGet();
        BreezeClient.LOGGER.info("[Breeze] interface browser closed ({} live)", left);
    }

    boolean isClosed() {
        return closed.get();
    }
}
