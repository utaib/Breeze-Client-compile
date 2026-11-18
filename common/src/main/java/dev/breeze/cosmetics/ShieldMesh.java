package dev.breeze.cosmetics;

import java.util.ArrayList;
import java.util.List;

/**
 * Minecraft's shield as quads, for drawing a custom shield with Breeze's own
 * texture where the game would draw its own (Custom Shield).
 *
 * The same two boxes as the game's shield model, a plate (12x22x1 at texture
 * offset 0,0) and a handle (2x6x6 at 26,0) on a 64x64 texture, unwrapped the
 * way Minecraft unwraps a model box, and already turned the way the game
 * turns the shield before drawing it (scale 1, -1, -1). Positions are in
 * blocks, texture coordinates are fractions, so a larger texture with the
 * same layout (ShieldArt) fits the same mesh.
 */
public final class ShieldMesh {

    /** One face: four corners (x, y, z, u, v each) and its normal. */
    public record Quad(float[] corners, float nx, float ny, float nz) {}

    private static final float TEX = 64f;
    private static final List<Quad> QUADS = build();

    private ShieldMesh() {}

    public static List<Quad> quads() {
        return QUADS;
    }

    private static List<Quad> build() {
        List<Quad> out = new ArrayList<>();
        box(out, 0, 0, -6, -11, -2, 12, 22, 1);
        box(out, 26, 0, -1, -3, -1, 2, 6, 6);
        return List.copyOf(out);
    }

    /** A model box as ModelPart.Cube builds it: six faces, Minecraft's UV layout. */
    private static void box(List<Quad> out, int texU, int texV, float x, float y, float z, float w, float h, float d) {
        float x1 = x + w, y1 = y + h, z1 = z + d;
        float[] v0 = {x, y, z}, v1 = {x1, y, z}, v2 = {x1, y1, z}, v3 = {x, y1, z};
        float[] v4 = {x, y, z1}, v5 = {x1, y, z1}, v6 = {x1, y1, z1}, v7 = {x, y1, z1};
        float u0 = texU, u1 = texU + d, u2 = texU + d + w, u3 = texU + d + w + w, u4 = texU + d + w + d, u5 = texU + d + w + d + w;
        float t0 = texV, t1 = texV + d, t2 = texV + d + h;
        face(out, new float[][] {v5, v4, v0, v1}, u1, t0, u2, t1, 0, -1, 0); // down
        face(out, new float[][] {v2, v3, v7, v6}, u2, t1, u3, t0, 0, 1, 0);  // up
        face(out, new float[][] {v0, v4, v7, v3}, u0, t1, u1, t2, -1, 0, 0); // west
        face(out, new float[][] {v1, v0, v3, v2}, u1, t1, u2, t2, 0, 0, -1); // north: the front, where pictures go
        face(out, new float[][] {v5, v1, v2, v6}, u2, t1, u4, t2, 1, 0, 0);  // east
        face(out, new float[][] {v4, v5, v6, v7}, u4, t1, u5, t2, 0, 0, 1);  // south
    }

    private static void face(List<Quad> out, float[][] v, float ua, float va, float ub, float vb, float nx, float ny, float nz) {
        // Corner texture coordinates in ModelPart.Polygon's order.
        float[][] uv = {{ub, va}, {ua, va}, {ua, vb}, {ub, vb}};
        float[] c = new float[20];
        for (int i = 0; i < 4; i++) {
            // In blocks, turned as the game turns the shield (scale 1, -1, -1).
            c[i * 5] = v[i][0] / 16f;
            c[i * 5 + 1] = -v[i][1] / 16f;
            c[i * 5 + 2] = -v[i][2] / 16f;
            c[i * 5 + 3] = uv[i][0] / TEX;
            c[i * 5 + 4] = uv[i][1] / TEX;
        }
        out.add(new Quad(c, nx, -ny, -nz));
    }
}
