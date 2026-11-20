package dev.breeze.render;

/**
 * The lines of one hitbox as Breeze draws it (Hitboxes module): the box's
 * twelve edges, and optionally the eye height as a ring round the box and
 * where the entity looks as a line from its eyes, as Minecraft's own F3+B
 * view shows them. Coordinates are relative to the camera, as every Breeze
 * world drawing is (OutlineGeometry, WorldRender.lines).
 */
public final class HitboxGeometry {

    private HitboxGeometry() {}

    /** How far the look line reaches, in blocks (Minecraft's own is 2). */
    public static final double LOOK_LENGTH = 2.0;

    /** The segments, 6 floats each; {@code segments.length / 6} of them. */
    public record Lines(float[] box, float[] eye, float[] look) {}

    public static Lines of(double x0, double y0, double z0, double x1, double y1, double z1,
                           double eyeY, double lookX, double lookY, double lookZ, boolean eye, boolean look) {
        float[] box = OutlineGeometry.boxEdges(x0, y0, z0, x1, y1, z1);
        float[] ring = null;
        if (eye && eyeY > y0 && eyeY < y1) {
            float a = (float) x0, c = (float) z0, d = (float) x1, f = (float) z1, e = (float) eyeY;
            ring = new float[] {a, e, c, d, e, c, d, e, c, d, e, f, d, e, f, a, e, f, a, e, f, a, e, c};
        }
        float[] line = null;
        double len = Math.sqrt(lookX * lookX + lookY * lookY + lookZ * lookZ);
        if (look && len > 1e-6) {
            double cx = (x0 + x1) / 2, cz = (z0 + z1) / 2, k = LOOK_LENGTH / len;
            line = new float[] {(float) cx, (float) eyeY, (float) cz,
                    (float) (cx + lookX * k), (float) (eyeY + lookY * k), (float) (cz + lookZ * k)};
        }
        return new Lines(box, ring, line);
    }

    /** What kind of thing an entity is, for which boxes are shown and in which colour. */
    public enum Kind { PLAYER, HOSTILE, PASSIVE, ITEM, PROJECTILE, OTHER }
}
