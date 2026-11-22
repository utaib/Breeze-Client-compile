package dev.breeze.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * One vertex of a 3D cosmetic, written to an entity vertex buffer through the
 * current pose. The vertex calls changed twice (1.20.5 took the pose for the
 * normal, 1.21 renamed them all), so versions/ has a copy per form; this is
 * the form up to 1.20.4.
 */
public final class Tris {

    private Tris() {}

    public static void vertex(VertexConsumer vc, PoseStack.Pose pose, float x, float y, float z, int argb,
                              float u, float v, int overlay, int light, float nx, float ny, float nz) {
        vc.vertex(pose.pose(), x, y, z)
                .color((argb >>> 16) & 0xFF, (argb >>> 8) & 0xFF, argb & 0xFF, argb >>> 24)
                .uv(u, v).overlayCoords(overlay).uv2(light)
                .normal(pose.normal(), nx, ny, nz).endVertex();
    }
}
