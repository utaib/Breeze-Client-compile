package dev.breeze.render;

/**
 * Lines and fills drawn in the world (Block Overlay, Ping Ghost), worked out
 * without Minecraft types so they can be tested.
 *
 * Every coordinate is relative to the camera, which sits at 0,0,0, in blocks.
 * A segment is two points (six floats), a quad four points (twelve floats).
 *
 * Minecraft's own lines have a width set by their render type, and before
 * 1.21.11 a render type with another width cannot be made from a mod on every
 * version (RenderType.create is not public before 1.20.1). So a line here is a
 * flat strip turned to face the camera and sized from its distance: the same
 * width in pixels on every version that can draw see-through quads.
 */
public final class OutlineGeometry {

    private OutlineGeometry() {}

    /** The twelve edges of a box, as segments (72 floats). */
    public static float[] boxEdges(double x0, double y0, double z0, double x1, double y1, double z1) {
        float a = (float) x0, b = (float) y0, c = (float) z0, d = (float) x1, e = (float) y1, f = (float) z1;
        return new float[] {
                // bottom
                a, b, c, d, b, c,
                d, b, c, d, b, f,
                d, b, f, a, b, f,
                a, b, f, a, b, c,
                // top
                a, e, c, d, e, c,
                d, e, c, d, e, f,
                d, e, f, a, e, f,
                a, e, f, a, e, c,
                // sides
                a, b, c, a, e, c,
                d, b, c, d, e, c,
                d, b, f, d, e, f,
                a, b, f, a, e, f,
        };
    }

    /** The six faces of a box, as quads (72 floats). */
    public static float[] boxFaces(double x0, double y0, double z0, double x1, double y1, double z1) {
        float a = (float) x0, b = (float) y0, c = (float) z0, d = (float) x1, e = (float) y1, f = (float) z1;
        return new float[] {
                a, b, c, d, b, c, d, b, f, a, b, f, // bottom
                a, e, c, a, e, f, d, e, f, d, e, c, // top
                a, b, c, a, e, c, d, e, c, d, b, c, // north
                a, b, f, d, b, f, d, e, f, a, e, f, // south
                a, b, c, a, b, f, a, e, f, a, e, c, // west
                d, b, c, d, e, c, d, e, f, d, b, f, // east
        };
    }

    /**
     * How many pixels one block covers at a distance of one block: the
     * screen's height over 2 tan(fov / 2). A block at distance d then covers
     * this divided by d.
     */
    public static double pixelsAtOneBlock(int screenHeightPx, double fovDegrees) {
        double fov = Math.max(1.0, Math.min(179.0, fovDegrees));
        return Math.max(1, screenHeightPx) / (2.0 * Math.tan(Math.toRadians(fov) / 2.0));
    }

    /**
     * A strip {@code widthPx} pixels wide along each segment, facing the
     * camera, as quads (twelve floats per segment). Size on screen goes with
     * depth along the camera's view ({@code look}, a unit vector), not with
     * straight-line distance, so a strip at the edge of the screen is as wide
     * as one in the middle. The ends are extended by
     * half the width so corners meet without a notch, and every point is
     * pulled towards the camera by about half the width (along its own line of
     * sight, so it does not move on screen) so the strip is not half hidden in
     * the faces it outlines. A segment of no length gives an empty quad.
     */
    public static float[] strips(float[] segments, int count, float widthPx, double pixelsAtOne,
                                 double lookX, double lookY, double lookZ) {
        float[] out = new float[count * 12];
        double px = Math.max(0.5, widthPx) / Math.max(1e-6, pixelsAtOne);
        for (int i = 0; i < count; i++) {
            int s = i * 6;
            double ax = segments[s], ay = segments[s + 1], az = segments[s + 2];
            double bx = segments[s + 3], by = segments[s + 4], bz = segments[s + 5];
            double dx = bx - ax, dy = by - ay, dz = bz - az;
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            int o = i * 12;
            if (len < 1e-6) {
                for (int k = 0; k < 12; k += 3) {
                    out[o + k] = (float) ax;
                    out[o + k + 1] = (float) ay;
                    out[o + k + 2] = (float) az;
                }
                continue;
            }
            dx /= len;
            dy /= len;
            dz /= len;
            double ha = 0.5 * px * Math.max(0.05, ax * lookX + ay * lookY + az * lookZ);
            double hb = 0.5 * px * Math.max(0.05, bx * lookX + by * lookY + bz * lookZ);

            // Square ends.
            ax -= dx * ha;
            ay -= dy * ha;
            az -= dz * ha;
            bx += dx * hb;
            by += dy * hb;
            bz += dz * hb;

            // Sideways: across the segment and across the line of sight to its middle.
            double mx = (ax + bx) * 0.5, my = (ay + by) * 0.5, mz = (az + bz) * 0.5;
            double sx = dy * mz - dz * my, sy = dz * mx - dx * mz, sz = dx * my - dy * mx;
            double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
            if (sl < 1e-9) {
                // Looking straight along the segment: any direction across it
                // will do. Across it and up, or across it and east if it is upright.
                sx = -dz;
                sy = 0;
                sz = dx;
                sl = Math.sqrt(sx * sx + sz * sz);
                if (sl < 1e-9) {
                    sx = 0;
                    sy = dz;
                    sz = -dy;
                    sl = Math.sqrt(sy * sy + sz * sz);
                }
            }
            sx /= sl;
            sy /= sl;
            sz /= sl;

            put(out, o, pull(ax + sx * ha, ay + sy * ha, az + sz * ha, ha));
            put(out, o + 3, pull(ax - sx * ha, ay - sy * ha, az - sz * ha, ha));
            put(out, o + 6, pull(bx - sx * hb, by - sy * hb, bz - sz * hb, hb));
            put(out, o + 9, pull(bx + sx * hb, by + sy * hb, bz + sz * hb, hb));
        }
        return out;
    }

    /** A colour cycling through the hues every four seconds, with the given alpha. */
    public static int rainbow(long timeMs, int alpha) {
        float hue = (float) (Math.floorMod(timeMs, 4000L) / 4000.0);
        int rgb = hsv(hue, 0.65f, 1.0f);
        return (Math.max(0, Math.min(255, alpha)) << 24) | rgb;
    }

    /** ARGB as four floats, 0 to 1, in the order red, green, blue, alpha. */
    public static float[] rgba(int argb) {
        return new float[] {
                ((argb >> 16) & 0xFF) / 255f,
                ((argb >> 8) & 0xFF) / 255f,
                (argb & 0xFF) / 255f,
                ((argb >>> 24) & 0xFF) / 255f,
        };
    }

    private static int hsv(float h, float s, float v) {
        float r, g, b;
        int i = (int) Math.floor(h * 6) % 6;
        float f = h * 6 - (float) Math.floor(h * 6);
        float p = v * (1 - s), q = v * (1 - f * s), t = v * (1 - (1 - f) * s);
        switch (i) {
            case 0: r = v; g = t; b = p; break;
            case 1: r = q; g = v; b = p; break;
            case 2: r = p; g = v; b = t; break;
            case 3: r = p; g = q; b = v; break;
            case 4: r = t; g = p; b = v; break;
            default: r = v; g = p; b = q; break;
        }
        return (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
    }

    private static double length(double x, double y, double z) {
        return Math.sqrt(x * x + y * y + z * z);
    }

    /** The point moved towards the camera by a little more than {@code by}, on its own line of sight. */
    private static double[] pull(double x, double y, double z, double by) {
        double d = length(x, y, z);
        if (d < 1e-6) return new double[] {x, y, z};
        double k = Math.max(0.0, 1.0 - Math.min(d * 0.5, by + 0.002) / d);
        return new double[] {x * k, y * k, z * k};
    }

    private static void put(float[] out, int at, double[] p) {
        out[at] = (float) p[0];
        out[at + 1] = (float) p[1];
        out[at + 2] = (float) p[2];
    }
}
