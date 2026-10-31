package dev.breeze.compat;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/**
 * GuiGraphics calls whose shape differs between Minecraft versions: texture
 * drawing, tooltips, the pose stack and raw quads. Everything else Breeze
 * draws (fill, text, scissor) is stable and called directly. versions/ has a
 * copy of this file per change; this is the 1.20.1 form.
 */
public final class Draw {

    private Draw() {}

    /**
     * Draws the (u, v, uw, vh) region of a texW x texH texture stretched over
     * (x, y, w, h).
     */
    public static void blit(GuiGraphics g, ResourceLocation tex, int x, int y, int w, int h,
                            float u, float v, int uw, int vh, int texW, int texH) {
        g.blit(tex, x, y, w, h, u, v, uw, vh, texW, texH);
    }

    /** The same, at an opacity from 0 to 1. */
    public static void blit(GuiGraphics g, ResourceLocation tex, int x, int y, int w, int h,
                            float u, float v, int uw, int vh, int texW, int texH, float alpha) {
        RenderSystem.enableBlend();
        g.setColor(1f, 1f, 1f, alpha);
        g.blit(tex, x, y, w, h, u, v, uw, vh, texW, texH);
        g.setColor(1f, 1f, 1f, 1f);
        // Blend is global GL state; left on, it leaks into whatever draws next.
        RenderSystem.disableBlend();
    }

    /** A one-line tooltip at the mouse. */
    public static void tooltip(GuiGraphics g, Font font, Component text, int mouseX, int mouseY) {
        g.renderTooltip(font, text, mouseX, mouseY);
    }

    /** Scales what is drawn until pop() by s, about (x, y). */
    public static void pushScaled(GuiGraphics g, float x, float y, float s) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(s, s, 1f);
        g.pose().translate(-x, -y, 0);
    }

    public static void pop(GuiGraphics g) {
        g.pose().popPose();
    }

    /**
     * The embedded browser's frame: an OpenGL texture in premultiplied alpha,
     * drawn over (0, 0, w, h) with its own blend so soft edges keep no dark
     * fringe. Only versions with an embedded browser call this.
     */
    public static void browserFrame(GuiGraphics g, int glTexture, int w, int h) {
        Matrix4f pose = g.pose().last().pose();
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, glTexture);
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        buffer.vertex(pose, 0, h, 0).uv(0f, 1f).color(255, 255, 255, 255).endVertex();
        buffer.vertex(pose, w, h, 0).uv(1f, 1f).color(255, 255, 255, 255).endVertex();
        buffer.vertex(pose, w, 0, 0).uv(1f, 0f).color(255, 255, 255, 255).endVertex();
        buffer.vertex(pose, 0, 0, 0).uv(0f, 0f).color(255, 255, 255, 255).endVertex();
        Tesselator.getInstance().end();
        RenderSystem.setShaderTexture(0, 0);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
    }
}
