package dev.breeze.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.breeze.compat.Tris;
import dev.breeze.cosmetics.model.CosmeticMotion;
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

    /** Cosmetic draws since start, for the self-test, in all and per cosmetic id. */
    public static volatile int draws;
    public static final Map<String, Integer> DRAWS_BY_ID = new ConcurrentHashMap<>();
    /** Cosmetics that threw while drawing, for the self-test. */
    public static volatile int failures;

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

    /**
     * walkSpeed: how fast the player's limbs swing (0 standing, about 1
     * sprinting), for a walking pet's clip and how fast a trail drifts back.
     */
    public static void draw(PoseStack poseStack, Sink sink, int light, UUID player, ModelPart head,
                            ModelPart body, ModelPart rightArm, float ageTicks, float walkSpeed, boolean invisible) {
        if (player == null || invisible) return;
        List<WornCosmetics.Worn> worn = WornCosmetics.get(player);
        if (worn.isEmpty()) return;
        for (WornCosmetics.Worn w : worn) {
            CosmeticModels.Loaded loaded = CosmeticModels.get(w);
            if (loaded == null) continue;
            try {
                drawOne(poseStack, sink, light, player, w, loaded, head, body, rightArm, ageTicks, walkSpeed);
                draws++;
                DRAWS_BY_ID.merge(w.id, 1, Integer::sum);
            } catch (Throwable t) {
                failures++;
                // One bad model must not take the player (or the frame) with it.
                dev.breeze.BreezeClient.LOGGER.warn("[Breeze] cosmetic {} failed to draw: {}", w.id, t.toString());
            }
        }
    }

    /** A flying pet's or a trail's state, per player and cosmetic. */
    private static final class Motion {
        CosmeticMotion.Flying flying;
        CosmeticMotion.Trail trail;
        long lastNanos;
    }

    private static final Map<String, Motion> MOTION = new ConcurrentHashMap<>();
    private static long lastPrune;

    private static void drawOne(PoseStack ps, Sink sink, int light, UUID player, WornCosmetics.Worn w,
                                CosmeticModels.Loaded loaded, ModelPart head, ModelPart body, ModelPart rightArm,
                                float ageTicks, float walkSpeed) {
        GlbModel model = loaded.model;
        CosmeticRig.Attachment a = w.attachment;
        CosmeticRig.Transform tr = w.transform;
        float hx = a.x + tr.offset[0], hy = a.y + tr.offset[1], hz = a.z + tr.offset[2];

        Motion motion = null;
        float dt = 0;
        if (a == CosmeticRig.Attachment.FLYING_PET || a == CosmeticRig.Attachment.TRAIL) {
            long now = System.nanoTime();
            motion = MOTION.computeIfAbsent(player + "/" + w.id, k -> new Motion());
            dt = motion.lastNanos == 0 ? 0 : Math.min(0.1f, (now - motion.lastNanos) / 1e9f);
            motion.lastNanos = now;
            prune(now);
        }

        String state = a.state;
        if (a == CosmeticRig.Attachment.SIDE) state = walkSpeed > 0.1f ? "walk" : "idle";
        float[] m;
        if (a == CosmeticRig.Attachment.FLYING_PET) {
            if (motion.flying == null) motion.flying = new CosmeticMotion.Flying(hx, hy, hz);
            state = motion.flying.step(dt);
            float[] at = motion.flying.pos;
            m = CosmeticRig.toPartAt(a, tr, loaded.bounds, at[0], at[1], at[2], motion.flying.yaw, motion.flying.roll, 1);
        } else {
            m = CosmeticRig.toPart(a, tr, loaded.bounds);
        }
        Pose pose = a == CosmeticRig.Attachment.TRAIL ? rest(w.id, model) : posed(w, model, state, ageTicks);

        ps.pushPose();
        switch (a.part) {
            case HEAD -> head.translateAndRotate(ps);
            case BODY -> body.translateAndRotate(ps);
            case RIGHT_ARM -> rightArm.translateAndRotate(ps);
            case PLAYER -> { }
        }
        ps.scale(PX, PX, PX);
        if (a == CosmeticRig.Attachment.TRAIL) {
            if (motion.trail == null) motion.trail = new CosmeticMotion.Trail(player.getLeastSignificantBits());
            motion.trail.step(dt, hx, hy, hz, Math.min(1f, walkSpeed) * 20f);
            for (CosmeticMotion.Copy c : motion.trail.copies) {
                float[] cm = CosmeticRig.toPartAt(a, tr, loaded.bounds, c.x, c.y, c.z, c.rotY, 0, c.scale());
                emit(ps, sink, light, model, loaded, pose, cm, c.opacity());
            }
        } else {
            emit(ps, sink, light, model, loaded, pose, m, 1f);
        }
        ps.popPose();
    }

    private static void emit(PoseStack ps, Sink sink, int light, GlbModel model, CosmeticModels.Loaded loaded,
                             Pose pose, float[] m, float opacity) {
        for (Pose.Part part : pose.parts) {
            GlbModel.Material mat = part.material >= 0 && part.material < model.materials.size()
                    ? model.materials.get(part.material) : null;
            ResourceLocation tex = mat != null && mat.image >= 0 && mat.image < loaded.images.length
                    ? loaded.images[mat.image] : CosmeticModels.white();
            float[] color = mat == null ? new float[]{1, 1, 1, 1} : mat.color;
            int argb = argb(new float[]{color[0], color[1], color[2], color[3] * opacity});
            boolean translucent = opacity < 1f || (mat != null && "BLEND".equals(mat.alphaMode));
            sink.draw(ps, tex, translucent, (vc, p) -> triangles(vc, p, part, m, argb, light));
        }
    }

    /** The model posed for a role at the moment's animation time, reused within one 1/30 s step. */
    private static Pose posed(WornCosmetics.Worn w, GlbModel model, String state, float ageTicks) {
        GlbModel.Clip clip = CosmeticRig.clipFor(model, w.roles, state);
        long frame = clip == null ? 0 : (long) (ageTicks / 20f * 30);
        Posed p = POSED.get(w.id);
        if (p == null || p.clip != clip || p.frame != frame) {
            p = new Posed(clip, frame, Pose.at(model, clip, frame / 30f));
            POSED.put(w.id, p);
        }
        return p.pose;
    }

    private static Pose rest(String id, GlbModel model) {
        Posed p = POSED.get(id + "#rest");
        if (p == null) {
            p = new Posed(null, 0, Pose.rest(model));
            POSED.put(id + "#rest", p);
        }
        return p.pose;
    }

    /** Forgets motion for players not drawn for a while (out of sight, left). */
    private static void prune(long now) {
        if (now - lastPrune < 5_000_000_000L) return;
        lastPrune = now;
        MOTION.values().removeIf(mo -> now - mo.lastNanos > 10_000_000_000L);
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
