package dev.breeze.cosmetics.model;

import java.util.Map;

/**
 * Where a 3D cosmetic sits on the player, matching the launcher's rig
 * (breeze launcher/src/cosmetics/rig.js, docs/COSMETICS.md) so a cosmetic is
 * in the same place in game as in the Wardrobe preview.
 *
 * The launcher works in skinview3d's space: Minecraft pixels, y up, the player
 * facing +Z, each part's origin where skinview3d puts it. Minecraft's model
 * parts are in pixels too but y down and facing -Z, and the body's origin is
 * the neck rather than the body's centre. {@link #toPart} gives the matrix
 * from the model's own space to the Minecraft part it moves with; a renderer
 * applies the part's pose, scales by 1/16 and draws the posed triangles
 * through it.
 */
public final class CosmeticRig {

    private CosmeticRig() {}

    /** The Minecraft model part a cosmetic moves with; PLAYER turns with the player but does not bob. */
    public enum Part { HEAD, BODY, RIGHT_ARM, PLAYER }

    /** Which point of the model's bounds sits on the anchor. */
    public enum Align { BOTTOM, FRONT, CENTER }

    public enum Attachment {
        HEAD(Part.HEAD, 0, 8, 0, Align.BOTTOM, 10, "idle"),
        SHOULDER(Part.BODY, 6, 6, 0, Align.BOTTOM, 6, "sit"),
        BACK(Part.BODY, 0, 1, -2, Align.FRONT, 16, "idle"),
        HAND(Part.RIGHT_ARM, -1, -10, 1, Align.CENTER, 8, "idle"),
        FEET(Part.PLAYER, 0, -16, 0, Align.BOTTOM, 12, "idle"),
        SIDE(Part.PLAYER, 13, -16, 2, Align.BOTTOM, 9, "walk"),
        FLYING_PET(Part.PLAYER, 12, 8, -3, Align.CENTER, 8, "fly"),
        TRAIL(Part.PLAYER, 0, -11, -4, Align.CENTER, 4, "idle");

        public final Part part;
        /** The anchor in skinview3d's space for that part. */
        public final float x, y, z;
        public final Align align;
        /** The model's longest side at scale 1, in pixels. */
        public final float size;
        /** The animation role it plays by default. */
        public final String state;

        Attachment(Part part, float x, float y, float z, Align align, float size, String state) {
            this.part = part;
            this.x = x;
            this.y = y;
            this.z = z;
            this.align = align;
            this.size = size;
            this.state = state;
        }

        private static final Map<String, Attachment> SLOT_DEFAULT = Map.of(
                "hat", HEAD, "wings", BACK, "cape", BACK, "back", BACK,
                "shield", HAND, "pet", SHOULDER, "aura", FEET, "trail", TRAIL);

        /** The cosmetic's attachment, else its slot's default, else HEAD (as the launcher). */
        public static Attachment of(String attachment, String slot) {
            if (attachment != null) {
                for (Attachment a : values()) if (a.name().equals(attachment)) return a;
            }
            Attachment d = slot == null ? null : SLOT_DEFAULT.get(slot);
            return d != null ? d : HEAD;
        }
    }

    /** The creator's (or the player's own) placement, clamped as the API clamps it. */
    public static final class Transform {
        public final float[] offset;
        public final float[] rotation;
        public final float scale;

        public Transform(float[] offset, float[] rotation, float scale) {
            this.offset = clamp3(offset, 32);
            this.rotation = clamp3(rotation, 360);
            this.scale = Float.isFinite(scale) ? Math.max(0.1f, Math.min(4f, scale)) : 1f;
        }

        public static final Transform NONE = new Transform(null, null, 1);

        private static float[] clamp3(float[] v, float limit) {
            float[] o = new float[3];
            for (int i = 0; i < 3; i++) {
                float x = v != null && i < v.length && Float.isFinite(v[i]) ? v[i] : 0;
                o[i] = Math.max(-limit, Math.min(limit, x));
            }
            return o;
        }
    }

    /**
     * Model space to skinview3d part space, as the launcher builds it: holder
     * at the anchor plus the offset, then the rotation (degrees, XYZ), then the
     * size fit, then the model shifted so its alignment point is the origin.
     * bounds is {minX, minY, minZ, maxX, maxY, maxZ} of the model at rest.
     */
    public static float[] toLauncherPart(Attachment a, Transform tr, float[] bounds) {
        float sx = bounds[3] - bounds[0], sy = bounds[4] - bounds[1], sz = bounds[5] - bounds[2];
        float longest = Math.max(sx, Math.max(sy, sz));
        float fitted = (a.size / (longest > 0 ? longest : 1)) * tr.scale;
        float cx = (bounds[0] + bounds[3]) / 2, cy = (bounds[1] + bounds[4]) / 2, cz = (bounds[2] + bounds[5]) / 2;
        float ax = cx, ay = cy, az = cz;
        if (a.align == Align.BOTTOM) ay = bounds[1];
        else if (a.align == Align.FRONT) az = bounds[5];
        float[] m = Mat4.translation(a.x + tr.offset[0], a.y + tr.offset[1], a.z + tr.offset[2]);
        m = Mat4.mul(m, Mat4.eulerXYZDegrees(tr.rotation[0], tr.rotation[1], tr.rotation[2]));
        m = Mat4.mul(m, Mat4.scale(fitted, fitted, fitted));
        return Mat4.mul(m, Mat4.translation(-ax, -ay, -az));
    }

    /**
     * skinview3d part space to Minecraft model part space (pixels): y and z
     * flip (y down, facing -Z); the body's origin moves from its centre to the
     * neck, six pixels up; the whole player's from its middle to the neck,
     * eight pixels up. Head and right arm pivot where Minecraft's do.
     */
    public static float[] launcherToMinecraft(Part part) {
        float up = switch (part) {
            case BODY -> 6;
            case PLAYER -> 8;
            default -> 0;
        };
        return Mat4.mul(Mat4.translation(0, up, 0), Mat4.scale(1, -1, -1));
    }

    /** Model space to the Minecraft part the attachment moves with, in pixels. */
    public static float[] toPart(Attachment a, Transform tr, float[] bounds) {
        return Mat4.mul(launcherToMinecraft(a.part), toLauncherPart(a, tr, bounds));
    }

    /** The clip for an animation role: that role's, else idle's, else the first clip; null for none. */
    public static GlbModel.Clip clipFor(GlbModel model, Map<String, String> roles, String state) {
        if (model.clips.isEmpty()) return null;
        GlbModel.Clip c = roles == null ? null : model.clip(roles.get(state));
        if (c == null && roles != null) c = model.clip(roles.get("idle"));
        return c != null ? c : model.clips.get(0);
    }
}
