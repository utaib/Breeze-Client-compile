package dev.breeze.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Drawing in the world for modules. The calls that differ between Minecraft
 * versions live here (versions/ has a copy per change); this is the 1.21
 * form: vertices are written with addVertex and setColor.
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
        LevelRenderer.renderLineBox(ps, ctx.consumers().getBuffer(RenderType.lines()), box, r, g, b, a);
        ps.popPose();
    }

    /**
     * Whether see-through quads can be drawn here, and so lines of any width
     * and filled faces. Settings that need them are offered only where true.
     */
    public static boolean canStyle() {
        return true;
    }

    /** Quads relative to the camera (OutlineGeometry), in one ARGB colour. */
    public static void quads(WorldCtx ctx, float[] pts, int quadCount, int argb) {
        if (ctx.consumers() == null || quadCount <= 0) return;
        float[] c = OutlineGeometry.rgba(argb);
        VertexConsumer vc = ctx.consumers().getBuffer(RenderType.debugQuads());
        PoseStack.Pose pose = ctx.pose().last();
        for (int i = 0; i < quadCount * 4; i++) {
            vc.addVertex(pose, pts[i * 3], pts[i * 3 + 1], pts[i * 3 + 2]).setColor(c[0], c[1], c[2], c[3]);
        }
    }

    /** Lines along segments relative to the camera, {@code widthPx} pixels wide on screen. */
    public static void lines(WorldCtx ctx, float[] segments, int count, int argb, float widthPx) {
        if (ctx.consumers() == null || count <= 0) return;
        Minecraft mc = Minecraft.getInstance();
        var look = mc.gameRenderer.getMainCamera().getLookVector();
        double ppb = OutlineGeometry.pixelsAtOneBlock(mc.getWindow().getHeight(), mc.options.fov().get());
        quads(ctx, OutlineGeometry.strips(segments, count, widthPx, ppb, look.x(), look.y(), look.z()), count, argb);
    }
}
