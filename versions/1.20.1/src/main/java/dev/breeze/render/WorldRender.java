package dev.breeze.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.world.phys.Vec3;

public final class WorldRender {

    private WorldRender() {}

    public static void text(WorldRenderContext ctx, double wx, double wy, double wz, String s, int color) {
        if (ctx.consumers() == null) return;
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        Vec3 cam = ctx.camera().getPosition();
        PoseStack ps = ctx.matrixStack();
        ps.pushPose();
        ps.translate(wx - cam.x, wy - cam.y, wz - cam.z);
        ps.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
        ps.scale(-0.025f, -0.025f, 0.025f);
        float x = -font.width(s) / 2.0f;
        font.drawInBatch(s, x, 0.0f, color, false, ps.last().pose(), ctx.consumers(), Font.DisplayMode.NORMAL, 0, 0xF000F0);
        ps.popPose();
    }
}
