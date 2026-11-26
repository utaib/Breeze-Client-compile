package dev.breeze.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.phys.Vec3;

/**
 * What a module needs to draw in the world: the pose stack, where to submit
 * what it draws (null when there is nowhere this frame) and the camera's
 * position. Breeze's own type, filled from whatever Fabric API hands over on
 * each version (compat/Hooks), so modules never see a Fabric or Minecraft
 * type that changes shape. This is the 26.2 form: Minecraft no longer hands
 * out buffers to draw into; drawing is submitted and Minecraft draws it later
 * in the frame.
 */
public record WorldCtx(PoseStack pose, SubmitNodeCollector submits, Vec3 cam) {

    /** Whether there is anywhere to draw this frame. */
    public boolean ready() {
        return submits != null;
    }
}
