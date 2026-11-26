package dev.breeze.render;

/**
 * A plain blocky player figure (Ping Ghost): legs, body, arms and head in the
 * player model's proportions, turned to where the player faces and looks.
 *
 * Coordinates are relative to the camera, in blocks; the figure stands with
 * its feet at (x, y, z). Its parts are boxes in the model's own pixels, a
 * player being 32 pixels tall; {@code height} scales that to the player's
 * real height, so a crouching player gives a shorter figure.
 *
 * Minecraft's angles: yaw 0 faces south (+z) and 90 faces west (-x); a
 * positive pitch looks down.
 */
public final class GhostModel {

    private GhostModel() {}

    /** The figure's faces (quads) and edges (segments), see OutlineGeometry. */
    public static final class Figure {
        public final float[] faces;
        public final float[] edges;

        Figure(float[] faces, float[] edges) {
            this.faces = faces;
            this.edges = edges;
        }

        public int quadCount() {
            return faces.length / 12;
        }

        public int edgeCount() {
            return edges.length / 6;
        }
    }

    /** Parts in model pixels: x across, y up from the feet, z forward. */
    private static final float[][] BODY_PARTS = {
            {-4, 0, -2, 0, 12, 2},   // right leg
            {0, 0, -2, 4, 12, 2},    // left leg
            {-4, 12, -2, 4, 24, 2},  // body
            {-8, 12, -2, -4, 24, 2}, // right arm
            {4, 12, -2, 8, 24, 2},   // left arm
    };
    private static final float[] HEAD = {-4, 24, -4, 4, 32, 4};
    private static final float NECK_Y = 24;
    public static final float MODEL_HEIGHT = 32;

    public static Figure build(double x, double y, double z, float bodyYaw, float headYaw, float headPitch, double height) {
        int parts = BODY_PARTS.length + 1;
        float[] faces = new float[parts * 72];
        float[] edges = new float[parts * 72];
        double scale = height / MODEL_HEIGHT;
        for (int i = 0; i < parts; i++) {
            boolean head = i == BODY_PARTS.length;
            float[] p = head ? HEAD : BODY_PARTS[i];
            double[][] corners = new double[8][];
            int c = 0;
            for (int ix = 0; ix < 2; ix++) {
                for (int iy = 0; iy < 2; iy++) {
                    for (int iz = 0; iz < 2; iz++) {
                        double lx = ix == 0 ? p[0] : p[3];
                        double ly = iy == 0 ? p[1] : p[4];
                        double lz = iz == 0 ? p[2] : p[5];
                        double yaw = bodyYaw;
                        if (head) {
                            double[] pitched = pitch(lx, ly, lz, headPitch);
                            lx = pitched[0];
                            ly = pitched[1];
                            lz = pitched[2];
                            yaw = headYaw;
                        }
                        double[] w = yaw(lx, lz, yaw);
                        corners[c++] = new double[] {x + w[0] * scale, y + ly * scale, z + w[1] * scale};
                    }
                }
            }
            System.arraycopy(boxFaces(corners), 0, faces, i * 72, 72);
            System.arraycopy(boxEdges(corners), 0, edges, i * 72, 72);
        }
        return new Figure(faces, edges);
    }

    /** A point in model pixels turned by a pitch about the neck. */
    static double[] pitch(double x, double y, double z, double pitchDeg) {
        double p = Math.toRadians(pitchDeg);
        double ry = y - NECK_Y;
        double ny = ry * Math.cos(p) - z * Math.sin(p);
        double nz = ry * Math.sin(p) + z * Math.cos(p);
        return new double[] {x, ny + NECK_Y, nz};
    }

    /** Model x and z turned to face along a yaw: model forward (+z) becomes the facing direction. */
    static double[] yaw(double x, double z, double yawDeg) {
        double a = Math.toRadians(yawDeg);
        double cos = Math.cos(a), sin = Math.sin(a);
        return new double[] {x * cos - z * sin, x * sin + z * cos};
    }

    /** Corner index for (x, y, z) choices of 0 or 1, as filled in build. */
    private static int k(int ix, int iy, int iz) {
        return ix * 4 + iy * 2 + iz;
    }

    private static float[] boxFaces(double[][] c) {
        int[][] quads = {
                {k(0, 0, 0), k(1, 0, 0), k(1, 0, 1), k(0, 0, 1)}, // bottom
                {k(0, 1, 0), k(0, 1, 1), k(1, 1, 1), k(1, 1, 0)}, // top
                {k(0, 0, 0), k(0, 1, 0), k(1, 1, 0), k(1, 0, 0)}, // back
                {k(0, 0, 1), k(1, 0, 1), k(1, 1, 1), k(0, 1, 1)}, // front
                {k(0, 0, 0), k(0, 0, 1), k(0, 1, 1), k(0, 1, 0)}, // one side
                {k(1, 0, 0), k(1, 1, 0), k(1, 1, 1), k(1, 0, 1)}, // other side
        };
        float[] out = new float[72];
        int o = 0;
        for (int[] q : quads) {
            for (int idx : q) {
                out[o++] = (float) c[idx][0];
                out[o++] = (float) c[idx][1];
                out[o++] = (float) c[idx][2];
            }
        }
        return out;
    }

    private static float[] boxEdges(double[][] c) {
        int[][] lines = {
                {k(0, 0, 0), k(1, 0, 0)}, {k(1, 0, 0), k(1, 0, 1)}, {k(1, 0, 1), k(0, 0, 1)}, {k(0, 0, 1), k(0, 0, 0)},
                {k(0, 1, 0), k(1, 1, 0)}, {k(1, 1, 0), k(1, 1, 1)}, {k(1, 1, 1), k(0, 1, 1)}, {k(0, 1, 1), k(0, 1, 0)},
                {k(0, 0, 0), k(0, 1, 0)}, {k(1, 0, 0), k(1, 1, 0)}, {k(1, 0, 1), k(1, 1, 1)}, {k(0, 0, 1), k(0, 1, 1)},
        };
        float[] out = new float[72];
        int o = 0;
        for (int[] l : lines) {
            for (int idx : l) {
                out[o++] = (float) c[idx][0];
                out[o++] = (float) c[idx][1];
                out[o++] = (float) c[idx][2];
            }
        }
        return out;
    }
}
