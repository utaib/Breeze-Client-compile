package dev.breeze.compat;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
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
 * form before 1.19.3: as 1.19.3 (items through the model-view stack), and
 * GuiComponent has no scissor yet, so the clip goes to RenderSystem in window
 * pixels (one level only; nested clips replace each other).
 */
public final class GuiGraphics {

    private final PoseStack pose;

    public GuiGraphics(PoseStack pose) {
        this.pose = pose;
    }

    public PoseStack pose() {
        return pose;
    }

    public int guiWidth() {
        return Minecraft.getInstance().getWindow().getGuiScaledWidth();
    }

    public int guiHeight() {
        return Minecraft.getInstance().getWindow().getGuiScaledHeight();
    }

    // ── Shapes ─────────────────────────────────────────────────────────────

    public void fill(int x1, int y1, int x2, int y2, int argb) {
        GuiComponent.fill(pose, x1, y1, x2, y2, argb);
    }

    public void fillGradient(int x1, int y1, int x2, int y2, int top, int bottom) {
        Protected.fillGradient(pose, x1, y1, x2, y2, top, bottom);
    }

    public void enableScissor(int x1, int y1, int x2, int y2) {
        var window = Minecraft.getInstance().getWindow();
        double scale = window.getGuiScale();
        int bottom = (int) (window.getHeight() - y2 * scale);
        RenderSystem.enableScissor((int) (x1 * scale), bottom,
                Math.max(0, (int) ((x2 - x1) * scale)), Math.max(0, (int) ((y2 - y1) * scale)));
    }

    public void disableScissor() {
        RenderSystem.disableScissor();
    }

    public void setColor(float r, float g, float b, float a) {
        RenderSystem.setShaderColor(r, g, b, a);
    }

    // ── Text (with a shadow unless told otherwise, as GuiGraphics does) ────

    public int drawString(Font font, String text, int x, int y, int color) {
        return drawString(font, text, x, y, color, true);
    }

    public int drawString(Font font, String text, int x, int y, int color, boolean shadow) {
        if (text == null) return 0;
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
        return shadow ? font.drawShadow(pose, text, x, y, color) : font.draw(pose, text, x, y, color);
    }

    public void drawCenteredString(Font font, String text, int x, int y, int color) {
        GuiComponent.drawCenteredString(pose, font, text, x, y, color);
    }

    public void drawCenteredString(Font font, Component text, int x, int y, int color) {
        GuiComponent.drawCenteredString(pose, font, text, x, y, color);
    }

    public void drawCenteredString(Font font, FormattedCharSequence text, int x, int y, int color) {
        GuiComponent.drawCenteredString(pose, font, text, x, y, color);
    }

    // ── Textures, items and tooltips ───────────────────────────────────────

    /** Part of a texture (u, v, uw by vh of a texW by texH image) stretched to w by h. */
    public void blit(ResourceLocation tex, int x, int y, int w, int h, float u, float v,
                     int uw, int vh, int texW, int texH) {
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, tex);
        GuiComponent.blit(pose, x, y, w, h, u, v, uw, vh, texW, texH);
    }

    public void renderItem(ItemStack stack, int x, int y) {
        withPose(() -> Minecraft.getInstance().getItemRenderer().renderAndDecorateItem(stack, x, y));
    }

    public void renderItemDecorations(Font font, ItemStack stack, int x, int y) {
        withPose(() -> Minecraft.getInstance().getItemRenderer().renderGuiItemDecorations(font, stack, x, y));
    }

    /** Runs draw with this pose applied to the model-view stack. */
    private void withPose(Runnable draw) {
        PoseStack view = RenderSystem.getModelViewStack();
        view.pushPose();
        view.mulPoseMatrix(pose.last().pose());
        RenderSystem.applyModelViewMatrix();
        try {
            draw.run();
        } finally {
            view.popPose();
            RenderSystem.applyModelViewMatrix();
        }
    }

    /** A tooltip at the mouse, drawn by the open screen (there is none in a world with no screen). */
    public void renderTooltip(Font font, Component text, int x, int y) {
        var screen = Minecraft.getInstance().screen;
        if (screen != null) screen.renderTooltip(pose, text, x, y);
    }

    /** GuiComponent's protected drawing, reached from a subclass. */
    private static final class Protected extends GuiComponent {
        static void fillGradient(PoseStack pose, int x1, int y1, int x2, int y2, int top, int bottom) {
            GuiComponent.fillGradient(pose, x1, y1, x2, y2, top, bottom);
        }
    }
}
