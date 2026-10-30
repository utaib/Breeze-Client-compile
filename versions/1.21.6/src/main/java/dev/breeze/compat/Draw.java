package dev.breeze.compat;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * GuiGraphics calls whose shape differs between Minecraft versions: texture
 * drawing, tooltips, the pose stack and raw quads. Everything else Breeze
 * draws (fill, text, scissor) is stable and called directly. versions/ has a
 * copy of this file per change; this is the 1.21.6 form: textures draw
 * through render pipelines, tooltips are queued for the end of the frame, and
 * the pose is a 2D matrix stack. (No embedded browser exists from 1.21.5 on.)
 */
public final class Draw {

    private Draw() {}

    /**
     * Draws the (u, v, uw, vh) region of a texW x texH texture stretched over
     * (x, y, w, h).
     */
    public static void blit(GuiGraphics g, ResourceLocation tex, int x, int y, int w, int h,
                            float u, float v, int uw, int vh, int texW, int texH) {
        g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, u, v, w, h, uw, vh, texW, texH);
    }

    /** The same, at an opacity from 0 to 1. */
    public static void blit(GuiGraphics g, ResourceLocation tex, int x, int y, int w, int h,
                            float u, float v, int uw, int vh, int texW, int texH, float alpha) {
        int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
        g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, u, v, w, h, uw, vh, texW, texH, (a << 24) | 0xFFFFFF);
    }

    /** A one-line tooltip at the mouse. */
    public static void tooltip(GuiGraphics g, Font font, Component text, int mouseX, int mouseY) {
        g.setTooltipForNextFrame(font, text, mouseX, mouseY);
    }

    /** Scales what is drawn until pop() by s, about (x, y). */
    public static void pushScaled(GuiGraphics g, float x, float y, float s) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(s, s);
        g.pose().translate(-x, -y);
    }

    public static void pop(GuiGraphics g) {
        g.pose().popMatrix();
    }
}
