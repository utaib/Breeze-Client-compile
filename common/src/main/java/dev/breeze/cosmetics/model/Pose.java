package dev.breeze.cosmetics.model;

import dev.breeze.cosmetics.model.GlbModel.Channel;
import dev.breeze.cosmetics.model.GlbModel.Clip;
import dev.breeze.cosmetics.model.GlbModel.Node;
import dev.breeze.cosmetics.model.GlbModel.Primitive;
import dev.breeze.cosmetics.model.GlbModel.Skin;

import java.util.ArrayList;
import java.util.List;

/**
 * A model posed at one moment: every mesh instance's triangles in the model's
 * own space, after its node transforms and, for skinned meshes, its skeleton.
 * This is what a renderer draws, three vertices per triangle.
 */
public final class Pose {

    /** One drawn primitive: xyz, normal and uv per corner, three corners per triangle. */
    public static final class Part {
        public final float[] positions;
        public final float[] normals;
        public final float[] uvs;
        public final int material;

        Part(float[] positions, float[] normals, float[] uvs, int material) {
            this.positions = positions;
            this.normals = normals;
            this.uvs = uvs;
            this.material = material;
        }

        public int corners() {
            return positions.length / 3;
        }
    }

    public final List<Part> parts;

    private Pose(List<Part> parts) {
        this.parts = parts;
    }

    /** The rest pose (no clip). */
    public static Pose rest(GlbModel m) {
        return at(m, null, 0);
    }

    /** The model at {@code time} seconds into the clip, looping; null clip for the rest pose. */
    public static Pose at(GlbModel m, Clip clip, float time) {
        int n = m.nodes.size();
        float[][] t = new float[n][];
        float[][] r = new float[n][];
        float[][] s = new float[n][];
        for (int i = 0; i < n; i++) {
            Node node = m.nodes.get(i);
            t[i] = node.t.clone();
            r[i] = node.r.clone();
            s[i] = node.s.clone();
        }
        if (clip != null && clip.duration > 0) {
            float tt = time % clip.duration;
            if (tt < 0) tt += clip.duration;
            for (Channel c : clip.channels) {
                float[] v = sample(c, tt);
                switch (c.path) {
                    case TRANSLATION -> t[c.node] = v;
                    case ROTATION -> r[c.node] = normalize(v);
                    case SCALE -> s[c.node] = v;
                }
            }
        }
        float[][] local = new float[n][];
        for (int i = 0; i < n; i++) {
            Node node = m.nodes.get(i);
            boolean animated = clip != null && animates(clip, i);
            local[i] = node.matrix != null && !animated ? node.matrix : Mat4.trs(t[i], r[i], s[i]);
        }
        float[][] world = new float[n][];
        for (int root : m.roots) walk(m, root, Mat4.identity(), local, world);

        List<Part> parts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Node node = m.nodes.get(i);
            if (node.mesh < 0 || world[i] == null) continue;
            Skin skin = node.skin >= 0 && node.skin < m.skins.size() ? m.skins.get(node.skin) : null;
            float[][] jointMats = null;
            if (skin != null) {
                jointMats = new float[skin.joints.length][];
                for (int j = 0; j < skin.joints.length; j++) {
                    float[] jw = world[skin.joints[j]];
                    float[] ibm = new float[16];
                    System.arraycopy(skin.inverseBind, j * 16, ibm, 0, 16);
                    jointMats[j] = Mat4.mul(jw == null ? Mat4.identity() : jw, ibm);
                }
            }
            for (Primitive p : m.meshes.get(node.mesh).primitives) {
                parts.add(bake(p, world[i], jointMats));
            }
        }
        return new Pose(parts);
    }

    private static void walk(GlbModel m, int i, float[] parent, float[][] local, float[][] world) {
        world[i] = Mat4.mul(parent, local[i]);
        for (int c : m.nodes.get(i).children) walk(m, c, world[i], local, world);
    }

    private static boolean animates(Clip clip, int node) {
        for (Channel c : clip.channels) if (c.node == node) return true;
        return false;
    }

    private static Part bake(Primitive p, float[] nodeWorld, float[][] joints) {
        int nv = p.vertices();
        float[] vp = new float[nv * 3];
        float[] vn = new float[nv * 3];
        for (int v = 0; v < nv; v++) {
            float x = p.positions[v * 3], y = p.positions[v * 3 + 1], z = p.positions[v * 3 + 2];
            float nx = 0, ny = 1, nz = 0;
            if (p.normals != null) {
                nx = p.normals[v * 3];
                ny = p.normals[v * 3 + 1];
                nz = p.normals[v * 3 + 2];
            }
            float[] mat = nodeWorld;
            if (joints != null && p.joints != null) {
                // Linear blend skinning: the weighted sum of the joint matrices.
                // The skinned node's own transform does not apply (glTF).
                mat = new float[16];
                float total = 0;
                for (int k = 0; k < 4; k++) {
                    float w = p.weights[v * 4 + k];
                    int j = p.joints[v * 4 + k];
                    if (w <= 0 || j < 0 || j >= joints.length) continue;
                    for (int e = 0; e < 16; e++) mat[e] += joints[j][e] * w;
                    total += w;
                }
                if (total <= 0) mat = nodeWorld;
                else if (Math.abs(total - 1) > 1e-4f) for (int e = 0; e < 16; e++) mat[e] /= total;
            }
            Mat4.point(mat, x, y, z, vp, v * 3);
            Mat4.direction(mat, nx, ny, nz, vn, v * 3);
        }
        int corners = p.indices.length;
        float[] pos = new float[corners * 3];
        float[] nrm = new float[corners * 3];
        float[] uv = new float[corners * 2];
        for (int c = 0; c < corners; c++) {
            int v = p.indices[c];
            System.arraycopy(vp, v * 3, pos, c * 3, 3);
            System.arraycopy(vn, v * 3, nrm, c * 3, 3);
            if (p.uvs != null) {
                uv[c * 2] = p.uvs[v * 2];
                uv[c * 2 + 1] = p.uvs[v * 2 + 1];
            }
        }
        return new Part(pos, nrm, uv, p.material);
    }

    /** Bounds of every corner: {minX, minY, minZ, maxX, maxY, maxZ}; zeros when empty. */
    public float[] bounds() {
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        boolean any = false;
        for (Part p : parts) {
            for (int i = 0; i < p.positions.length; i += 3) {
                any = true;
                for (int k = 0; k < 3; k++) {
                    b[k] = Math.min(b[k], p.positions[i + k]);
                    b[k + 3] = Math.max(b[k + 3], p.positions[i + k]);
                }
            }
        }
        return any ? b : new float[6];
    }

    // ── Sampling ─────────────────────────────────────────────────────────

    static float[] sample(Channel c, float time) {
        int width = c.path == GlbModel.Path.ROTATION ? 4 : 3;
        boolean cubic = c.interpolation.equals("CUBICSPLINE");
        int stride = cubic ? width * 3 : width;
        int off = cubic ? width : 0; // the value sits between the in and out tangents
        float[] ts = c.times;
        if (time <= ts[0] || ts.length == 1) return slice(c.values, off, width);
        int last = ts.length - 1;
        if (time >= ts[last]) return slice(c.values, last * stride + off, width);
        int k = 0;
        while (k < last && ts[k + 1] < time) k++;
        float t0 = ts[k], t1 = ts[k + 1];
        float u = t1 > t0 ? (time - t0) / (t1 - t0) : 0;
        float[] a = slice(c.values, k * stride + off, width);
        float[] b = slice(c.values, (k + 1) * stride + off, width);
        if (c.interpolation.equals("STEP")) return a;
        if (cubic) {
            // Hermite with the stored tangents, scaled by the key spacing.
            float dt = t1 - t0;
            float[] ma = slice(c.values, k * stride + 2 * width, width);   // out-tangent of k
            float[] mb = slice(c.values, (k + 1) * stride, width);         // in-tangent of k+1
            float u2 = u * u, u3 = u2 * u;
            float h00 = 2 * u3 - 3 * u2 + 1, h10 = u3 - 2 * u2 + u, h01 = -2 * u3 + 3 * u2, h11 = u3 - u2;
            float[] o = new float[width];
            for (int i = 0; i < width; i++) o[i] = h00 * a[i] + h10 * dt * ma[i] + h01 * b[i] + h11 * dt * mb[i];
            return o;
        }
        return c.path == GlbModel.Path.ROTATION ? slerp(a, b, u) : lerp(a, b, u);
    }

    private static float[] slice(float[] v, int off, int n) {
        float[] o = new float[n];
        System.arraycopy(v, off, o, 0, n);
        return o;
    }

    private static float[] lerp(float[] a, float[] b, float u) {
        float[] o = new float[a.length];
        for (int i = 0; i < a.length; i++) o[i] = a[i] + (b[i] - a[i]) * u;
        return o;
    }

    static float[] slerp(float[] a, float[] b, float u) {
        float dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
        float[] bb = b.clone();
        if (dot < 0) {
            dot = -dot;
            for (int i = 0; i < 4; i++) bb[i] = -bb[i];
        }
        if (dot > 0.9995f) return normalize(lerp(a, bb, u));
        double theta = Math.acos(dot);
        double sin = Math.sin(theta);
        float wa = (float) (Math.sin((1 - u) * theta) / sin);
        float wb = (float) (Math.sin(u * theta) / sin);
        return new float[]{wa * a[0] + wb * bb[0], wa * a[1] + wb * bb[1], wa * a[2] + wb * bb[2], wa * a[3] + wb * bb[3]};
    }

    private static float[] normalize(float[] q) {
        float l = (float) Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        if (l < 1e-8f) return new float[]{0, 0, 0, 1};
        return new float[]{q[0] / l, q[1] / l, q[2] / l, q[3] / l};
    }
}
