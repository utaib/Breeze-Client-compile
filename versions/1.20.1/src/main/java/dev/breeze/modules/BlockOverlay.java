package dev.breeze.modules;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.breeze.Category;
import dev.breeze.Module;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class BlockOverlay extends Module {

    public BlockOverlay() {
        super("Block Overlay", Category.VISUAL, "Outlines the targeted block.", KEY_NONE);
    }

    @Override
    protected void onWorldRender(WorldRenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        HitResult hr = mc.hitResult;
        if (hr == null || hr.getType() != HitResult.Type.BLOCK || !(hr instanceof BlockHitResult bhr)) return;
        if (ctx.consumers() == null) return;
        BlockPos pos = bhr.getBlockPos();
        Vec3 cam = ctx.camera().getPosition();
        PoseStack ps = ctx.matrixStack();
        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        LevelRenderer.renderLineBox(ps, ctx.consumers().getBuffer(RenderType.lines()), new AABB(pos), 0.2f, 1.0f, 0.8f, 1.0f);
        ps.popPose();
    }
}
