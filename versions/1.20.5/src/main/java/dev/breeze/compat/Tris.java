package dev.breeze.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * One vertex of a 3D cosmetic, written to an entity vertex buffer through the
 * current pose. This is the 1.20.5 and 1.20.6 form: position and normal both
 * take the pose itself.
 */
public final class Tris {

    private Tris() {}

    public static void vertex(VertexConsumer vc, PoseStack.Pose pose, float x, float y, float z, int argb,
                              float u, float v, int overlay, int light, float nx, float ny, float nz) {
        vc.vertex(pose, x, y, z)
                .color((argb >>> 16) & 0xFF, (argb >>> 8) & 0xFF, argb & 0xFF, argb >>> 24)
                .uv(u, v).overlayCoords(overlay).uv2(light)
                .normal(pose, nx, ny, nz).endVertex();
    }
}
