package dev.breeze.web;

import com.cinemamod.mcef.MCEFBrowser;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * How the page's picture is found and drawn on Minecraft 1.21.5 and later.
 * Minecraft no longer draws an OpenGL texture by number there, so the browser
 * builds for these versions (MCEF 2.1.6 and 2.1.7 for 1.21.5 to 1.21.10, 2.2.0
 * for 26.1.x, Rinku 3 for 1.21.11 and 26.2+, read in CI with
 * browser-facts.sh) register the page as a named texture, drawn like any other
 * picture in the interface. Rinku names its classes and this method
 * differently (versions/shared/rinku and texture-identifier rename them).
 */
final class BrowserFrame {

    private BrowserFrame() {}

    /** True once Chromium has painted at least one frame. */
    static boolean painted(MCEFBrowser browser) {
        return browser.isTextureReady();
    }

    /**
     * The page over the whole screen. The texture is the browser's own size in
     * pixels, drawn whole (u 0 to 1) over the screen in GUI units.
     */
    static void draw(GuiGraphics g, MCEFBrowser browser, int guiWidth, int guiHeight, int pixelWidth, int pixelHeight) {
        ResourceLocation texture = browser.getTextureLocation();
        if (texture == null) return;
        int w = Math.max(1, pixelWidth);
        int h = Math.max(1, pixelHeight);
        dev.breeze.compat.Draw.blit(g, texture, 0, 0, guiWidth, guiHeight, 0f, 0f, w, h, w, h);
    }
}
