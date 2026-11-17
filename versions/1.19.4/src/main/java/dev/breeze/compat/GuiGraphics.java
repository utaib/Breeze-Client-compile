package dev.breeze.compat;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

/**
 * Minecraft's GuiGraphics as far as Breeze uses it, for versions before 1.20,
 * which draw with a PoseStack and GuiComponent's static methods instead. The
 * version's renames.txt points net.minecraft.client.gui.GuiGraphics here, so
 * the modules and screens draw with the same calls on every version. Wrap the
 * pose stack Minecraft hands over: new GuiGraphics(poseStack). This is the
 * 1.19.4 form.
 */
public final class GuiGraphics {

    private final PoseStack pose;

    public GuiGraphics(PoseStack pose) {
        this.pose = pose;
    }

    public PoseStack pose() {
        return pose;
    }

    /**
     * Ends the gathered fills now. For code inside drawManaged that is about
     * to draw with Minecraft directly instead of through this object.
     */
    public void flush() {
        flushFills();
    }

    public int guiWidth() {
        return Minecraft.getInstance().getWindow().getGuiScaledWidth();
    }

    public int guiHeight() {
        return Minecraft.getInstance().getWindow().getGuiScaledHeight();
    }

    // ── Shapes ─────────────────────────────────────────────────────────────

    /** Inside drawManaged: fills are gathered into one draw. */
    private boolean managed;
    /** Whether a gathered draw has fills in it that are not on screen yet. */
    private boolean gathering;

    public void fill(int x1, int y1, int x2, int y2, int argb) {
        if (managed && gather(x1, y1, x2, y2, argb)) return;
        GuiComponent.fill(pose, x1, y1, x2, y2, argb);
    }

    public void fillGradient(int x1, int y1, int x2, int y2, int top, int bottom) {
        flushFills();
        Protected.gradient(pose, x1, y1, x2, y2, top, bottom);
    }

    /**
     * GuiGraphics.drawManaged's shape. These versions draw each fill through
     * GuiComponent as a draw of its own, so a rounded panel was nine draws
     * and the HUD hundreds a frame. Inside this, fills are gathered and go
     * out as one draw (2.29.0): when the block ends, and before anything
     * else is drawn through this object, so what is drawn keeps its order.
     */
    public void drawManaged(Runnable draw) {
        if (managed) {
            draw.run();
            return;
        }
        managed = true;
        try {
            draw.run();
        } finally {
            flushFills();
            managed = false;
        }
    }

    /**
     * Adds a fill to the gathered draw, as GuiComponent.fill writes one, in
     * Minecraft's shared buffer. Nothing else uses that buffer meanwhile:
     * every other draw through this object puts the fills on screen first.
     */
    private boolean gather(int x1, int y1, int x2, int y2, int argb) {
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        if (!gathering) {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            gathering = true;
        }
        if (x1 < x2) { int t = x1; x1 = x2; x2 = t; }
        if (y1 < y2) { int t = y1; y1 = y2; y2 = t; }
        float a = (argb >> 24 & 255) / 255f;
        float r = (argb >> 16 & 255) / 255f;
        float g = (argb >> 8 & 255) / 255f;
        float b = (argb & 255) / 255f;
        buffer.vertex(pose.last().pose(), (float) x1, (float) y2, 0f).color(r, g, b, a).endVertex();
        buffer.vertex(pose.last().pose(), (float) x2, (float) y2, 0f).color(r, g, b, a).endVertex();
        buffer.vertex(pose.last().pose(), (float) x2, (float) y1, 0f).color(r, g, b, a).endVertex();
        buffer.vertex(pose.last().pose(), (float) x1, (float) y1, 0f).color(r, g, b, a).endVertex();
        return true;
    }

    /** Puts the gathered fills on screen. Called before anything else is drawn. */
    private void flushFills() {
        if (!gathering) return;
        gathering = false;
        Tesselator.getInstance().end();
        RenderSystem.disableBlend();
    }

    public void enableScissor(int x1, int y1, int x2, int y2) {
        flushFills();
        GuiComponent.enableScissor(x1, y1, x2, y2);
    }

    public void disableScissor() {
        flushFills();
        GuiComponent.disableScissor();
    }

    public void setColor(float r, float g, float b, float a) {
        flushFills();
        RenderSystem.setShaderColor(r, g, b, a);
    }

    // ── Text (with a shadow unless told otherwise, as GuiGraphics does) ────

    public int drawString(Font font, String text, int x, int y, int color) {
        return drawString(font, text, x, y, color, true);
    }

    public int drawString(Font font, String text, int x, int y, int color, boolean shadow) {
        if (text == null) return 0;
        flushFills();
        return shadow ? font.drawShadow(pose, text, x, y, color) : font.draw(pose, text, x, y, color);
    }

    public int drawString(Font font, Component text, int x, int y, int color) {
        return drawString(font, text, x, y, color, true);
    }

    public int drawString(Font font, Component text, int x, int y, int color, boolean shadow) {
        return drawString(font, text.getVisualOrderText(), x, y, color, shadow);
    }

    public int drawString(Font font, FormattedCharSequence text, int x, int y, int color) {
        return drawString(font, text, x, y, color, true);
    }

    public int drawString(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow) {
        flushFills();
        return shadow ? font.drawShadow(pose, text, x, y, color) : font.draw(pose, text, x, y, color);
    }

    public void drawCenteredString(Font font, String text, int x, int y, int color) {
        flushFills();
        GuiComponent.drawCenteredString(pose, font, text, x, y, color);
    }

    public void drawCenteredString(Font font, Component text, int x, int y, int color) {
        flushFills();
        GuiComponent.drawCenteredString(pose, font, text, x, y, color);
    }

    public void drawCenteredString(Font font, FormattedCharSequence text, int x, int y, int color) {
        flushFills();
        GuiComponent.drawCenteredString(pose, font, text, x, y, color);
    }

    // ── Textures, items and tooltips ───────────────────────────────────────

    /** Part of a texture (u, v, uw by vh of a texW by texH image) stretched to w by h. */
    public void blit(ResourceLocation tex, int x, int y, int w, int h, float u, float v,
                     int uw, int vh, int texW, int texH) {
        flushFills();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, tex);
        GuiComponent.blit(pose, x, y, w, h, u, v, uw, vh, texW, texH);
    }

    public void renderItem(ItemStack stack, int x, int y) {
        flushFills();
        Minecraft.getInstance().getItemRenderer().renderAndDecorateItem(pose, stack, x, y);
    }

    public void renderItemDecorations(Font font, ItemStack stack, int x, int y) {
        flushFills();
        Minecraft.getInstance().getItemRenderer().renderGuiItemDecorations(pose, font, stack, x, y);
    }

    /** A tooltip at the mouse, drawn by the open screen (there is none in a world with no screen). */
    public void renderTooltip(Font font, Component text, int x, int y) {
        flushFills();
        var screen = Minecraft.getInstance().screen;
        if (screen != null) screen.renderTooltip(pose, text, x, y);
    }

    /** GuiComponent's protected drawing, reached from a subclass. */
    private static final class Protected extends GuiComponent {
        static void gradient(PoseStack pose, int x1, int y1, int x2, int y2, int top, int bottom) {
            GuiComponent.fillGradient(pose, x1, y1, x2, y2, top, bottom);
        }
    }
}
