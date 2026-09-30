package dev.breeze.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Drawing in the world for modules. The calls that differ between Minecraft
 * versions live here (versions/ has a copy per change); this is the 1.21.9
 * form: line boxes take the current pose rather than the stack.
 */
public final class WorldRender {

    private WorldRender() {}

    /** A label facing the camera at a world position, drawn through walls at full brightness. */
    public static void text(WorldCtx ctx, double wx, double wy, double wz, String s, int color) {
        if (ctx.consumers() == null) return;
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        Vec3 cam = ctx.cam();
        PoseStack ps = ctx.pose();
        ps.pushPose();
        ps.translate(wx - cam.x, wy - cam.y, wz - cam.z);
        ps.mulPose(mc.gameRenderer.getMainCamera().rotation());
        ps.scale(-0.025f, -0.025f, 0.025f);
        float x = -font.width(s) / 2.0f;
        font.drawInBatch(s, x, 0.0f, color, false, ps.last().pose(), ctx.consumers(), Font.DisplayMode.NORMAL, 0, 0xF000F0);
        ps.popPose();
    }

    /** The edges of a box in world coordinates. */
    public static void lineBox(WorldCtx ctx, AABB box, float r, float g, float b, float a) {
        if (ctx.consumers() == null) return;
        Vec3 cam = ctx.cam();
        PoseStack ps = ctx.pose();
        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        ShapeRenderer.renderLineBox(ps.last(), ctx.consumers().getBuffer(RenderType.lines()), box, r, g, b, a);
        ps.popPose();
    }
}
