package dev.breeze.web;

import com.cinemamod.mcef.MCEFBrowser;
import com.cinemamod.mcef.MCEFRenderer;
import net.minecraft.client.gui.GuiGraphics;

/**
 * How the page's picture is found and drawn, the one part of the browser that
 * changes with the browser build. Here (MCEF 2.1.6, up to Minecraft 1.21.4)
 * the renderer hands out an OpenGL texture number, drawn directly.
 * versions/shared/browser-texture replaces this file for the builds of
 * Minecraft 1.21.5 and later, which no longer draw by number.
 */
final class BrowserFrame {

    private BrowserFrame() {}

    /** True once Chromium has painted at least one frame. */
    static boolean painted(MCEFBrowser browser) {
        MCEFRenderer r = browser.getRenderer();
        return r != null && r.getTextureID() != 0;
    }

    /**
     * The page over the whole screen. CEF paints premultiplied alpha into a
     * transparent surface, so the blend is ONE, ONE_MINUS_SRC_ALPHA (in
     * Draw.browserFrame): the world shows through wherever the page is clear.
     */
    static void draw(GuiGraphics g, MCEFBrowser browser, int guiWidth, int guiHeight, int pixelWidth, int pixelHeight) {
        dev.breeze.compat.Draw.browserFrame(g, browser.getRenderer().getTextureID(), guiWidth, guiHeight);
    }
}
