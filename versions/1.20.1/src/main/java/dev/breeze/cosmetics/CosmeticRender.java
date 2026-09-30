package dev.breeze.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.breeze.compat.Tris;
import dev.breeze.cosmetics.model.CosmeticRig;
import dev.breeze.cosmetics.model.GlbModel;
import dev.breeze.cosmetics.model.Mat4;
import dev.breeze.cosmetics.model.Pose;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws a player's 3D cosmetics. Called from the cape layer (CapeLayerMixin,
 * one form per rendering era), which Minecraft runs for every player it draws,
 * with the pose stack in the player model's space. Each cosmetic is posed at
 * the moment's animation time, placed with {@link CosmeticRig} on the part it
 * moves with (head, body, right arm, or the player as a whole), and written as
 * triangles through the era's {@link Sink}.
 *
 * Minecraft's entity render types take quads, so each triangle goes in as a
 * quad whose last corner repeats its third.
 */
public final class CosmeticRender {

    /** How one era turns geometry into draw calls: a buffer directly, or a submitted callback. */
    public interface Sink {
        void draw(PoseStack poseStack, ResourceLocation texture, boolean translucent, Geometry geometry);
    }

    /** Writes vertices through the pose the sink provides. */
    public interface Geometry {
        void write(VertexConsumer vc, PoseStack.Pose pose);
    }

    private static final int NO_OVERLAY = 655360; // OverlayTexture.NO_OVERLAY, the same in every version
    private static final float PX = 1f / 16f;
    /** Posed triangles per cosmetic, reused while the animation time has not moved on. */
    private static final Map<String, Posed> POSED = new ConcurrentHashMap<>();

    /** Cosmetic draws since start, for the self-test. */
    public static volatile int draws;

    private CosmeticRender() {}

    private static final class Posed {
        final GlbModel.Clip clip;
        final long frame;
        final Pose pose;

        Posed(GlbModel.Clip clip, long frame, Pose pose) {
            this.clip = clip;
            this.frame = frame;
            this.pose = pose;
        }
    }

    public static void draw(PoseStack poseStack, Sink sink, int light, UUID player, ModelPart head,
                            ModelPart body, ModelPart rightArm, float ageTicks, boolean invisible) {
        if (player == null || invisible) return;
        List<WornCosmetics.Worn> worn = WornCosmetics.get(player);
        if (worn.isEmpty()) return;
        for (WornCosmetics.Worn w : worn) {
            CosmeticModels.Loaded loaded = CosmeticModels.get(w);
            if (loaded == null) continue;
            try {
                drawOne(poseStack, sink, light, w, loaded, head, body, rightArm, ageTicks);
                draws++;
            } catch (Throwable t) {
                // One bad model must not take the player (or the frame) with it.
                dev.breeze.BreezeClient.LOGGER.warn("[Breeze] cosmetic {} failed to draw: {}", w.id, t.toString());
            }
        }
    }

    private static void drawOne(PoseStack ps, Sink sink, int light, WornCosmetics.Worn w, CosmeticModels.Loaded loaded,
                                ModelPart head, ModelPart body, ModelPart rightArm, float ageTicks) {
        GlbModel model = loaded.model;
        GlbModel.Clip clip = CosmeticRig.clipFor(model, w.roles, w.attachment.state);
        float seconds = ageTicks / 20f;
        long frame = clip == null ? 0 : (long) (seconds * 30);
        Posed p = POSED.get(w.id);
        if (p == null || p.clip != clip || p.frame != frame) {
            p = new Posed(clip, frame, Pose.at(model, clip, frame / 30f));
            POSED.put(w.id, p);
        }
        float[] m = CosmeticRig.toPart(w.attachment, w.transform, loaded.bounds);

        ps.pushPose();
        switch (w.attachment.part) {
            case HEAD -> head.translateAndRotate(ps);
            case BODY -> body.translateAndRotate(ps);
            case RIGHT_ARM -> rightArm.translateAndRotate(ps);
            case PLAYER -> { }
        }
        ps.scale(PX, PX, PX);
        for (Pose.Part part : p.pose.parts) {
            GlbModel.Material mat = part.material >= 0 && part.material < model.materials.size()
                    ? model.materials.get(part.material) : null;
            ResourceLocation tex = mat != null && mat.image >= 0 && mat.image < loaded.images.length
                    ? loaded.images[mat.image] : CosmeticModels.white();
            int argb = mat == null ? 0xFFFFFFFF : argb(mat.color);
            boolean translucent = mat != null && "BLEND".equals(mat.alphaMode);
            sink.draw(ps, tex, translucent, (vc, pose) -> triangles(vc, pose, part, m, argb, light));
        }
        ps.popPose();
    }

    private static void triangles(VertexConsumer vc, PoseStack.Pose pose, Pose.Part part, float[] m, int argb, int light) {
        float[] p = new float[3];
        float[] n = new float[3];
        int corners = part.corners();
        for (int t = 0; t + 2 < corners; t += 3) {
            for (int k = 0; k < 4; k++) {
                int c = t + Math.min(k, 2);
                Mat4.point(m, part.positions[c * 3], part.positions[c * 3 + 1], part.positions[c * 3 + 2], p, 0);
                Mat4.direction(m, part.normals[c * 3], part.normals[c * 3 + 1], part.normals[c * 3 + 2], n, 0);
                Tris.vertex(vc, pose, p[0], p[1], p[2], argb, part.uvs[c * 2], part.uvs[c * 2 + 1],
                        NO_OVERLAY, light, n[0], n[1], n[2]);
            }
        }
    }

    private static int argb(float[] rgba) {
        int r = clamp(rgba[0]), g = clamp(rgba[1]), b = clamp(rgba[2]), a = clamp(rgba[3]);
        return a << 24 | r << 16 | g << 8 | b;
    }

    private static int clamp(float f) {
        return Math.max(0, Math.min(255, Math.round(f * 255)));
    }
}
