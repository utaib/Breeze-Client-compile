package dev.breeze.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;

/**
 * What a module needs to draw in the world after entities: the pose stack in
 * camera space, the buffers to draw into (null when there are none this frame)
 * and the camera's position. Breeze's own type, filled from whatever Fabric
 * API hands over on each version (compat/Hooks), so modules never see a
 * Fabric or Minecraft type that changes shape.
 */
public record WorldCtx(PoseStack pose, MultiBufferSource consumers, Vec3 cam) {

    /** Whether there is anything to draw into this frame. */
    public boolean ready() {
        return consumers != null;
    }
}
