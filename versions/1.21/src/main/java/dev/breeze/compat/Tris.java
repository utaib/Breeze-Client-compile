package dev.breeze.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * One vertex of a 3D cosmetic, written to an entity vertex buffer through the
 * current pose. This is the 1.21 and later form (addVertex and set*).
 */
public final class Tris {

    private Tris() {}

    public static void vertex(VertexConsumer vc, PoseStack.Pose pose, float x, float y, float z, int argb,
                              float u, float v, int overlay, int light, float nx, float ny, float nz) {
        vc.addVertex(pose, x, y, z).setColor(argb).setUv(u, v).setOverlay(overlay).setLight(light)
                .setNormal(pose, nx, ny, nz);
    }
}
