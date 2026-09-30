package dev.breeze.modules;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.breeze.Category;
import dev.breeze.Module;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class LootBeams extends Module {

    public LootBeams() {
        super("Loot Beams", Category.VISUAL, "Renders a beam over dropped items.", KEY_NONE);
    }

    @Override
    protected void onWorldRender(WorldRenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || ctx.consumers() == null) return;
        Vec3 cam = ctx.camera().getPosition();
        PoseStack ps = ctx.matrixStack();
        VertexConsumer vc = ctx.consumers().getBuffer(RenderType.lines());
        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof ItemEntity)) continue;
            double x = e.getX();
            double y = e.getY();
            double z = e.getZ();
            LevelRenderer.renderLineBox(ps, vc, new AABB(x - 0.03, y, z - 0.03, x + 0.03, y + 1.2, z + 0.03), 1.0f, 0.85f, 0.1f, 1.0f);
        }
        ps.popPose();
    }
}
