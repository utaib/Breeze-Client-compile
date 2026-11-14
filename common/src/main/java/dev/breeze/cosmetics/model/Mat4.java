package dev.breeze.cosmetics.model;

/**
 * 4x4 matrices as 16 floats, column-major, the layout glTF uses.
 * Element (row r, column c) is m[c * 4 + r].
 */
public final class Mat4 {

    private Mat4() {}

    public static float[] identity() {
        return new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    /** a * b: b applied first. */
    public static float[] mul(float[] a, float[] b) {
        float[] o = new float[16];
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
                float s = 0;
                for (int k = 0; k < 4; k++) s += a[k * 4 + r] * b[c * 4 + k];
                o[c * 4 + r] = s;
            }
        }
        return o;
    }

    /** Translation t, rotation quaternion q (x, y, z, w), scale s: T * R * S. */
    public static float[] trs(float[] t, float[] q, float[] s) {
        float x = q[0], y = q[1], z = q[2], w = q[3];
        float xx = x * x, yy = y * y, zz = z * z;
        float xy = x * y, xz = x * z, yz = y * z, wx = w * x, wy = w * y, wz = w * z;
        return new float[]{
                (1 - 2 * (yy + zz)) * s[0], (2 * (xy + wz)) * s[0], (2 * (xz - wy)) * s[0], 0,
                (2 * (xy - wz)) * s[1], (1 - 2 * (xx + zz)) * s[1], (2 * (yz + wx)) * s[1], 0,
                (2 * (xz + wy)) * s[2], (2 * (yz - wx)) * s[2], (1 - 2 * (xx + yy)) * s[2], 0,
                t[0], t[1], t[2], 1,
        };
    }

    public static float[] translation(float x, float y, float z) {
        float[] m = identity();
        m[12] = x;
        m[13] = y;
        m[14] = z;
        return m;
    }

    public static float[] scale(float x, float y, float z) {
        float[] m = identity();
        m[0] = x;
        m[5] = y;
        m[10] = z;
        return m;
    }

    public static float[] rotationX(float rad) {
        float c = (float) Math.cos(rad), s = (float) Math.sin(rad);
        return new float[]{1, 0, 0, 0, 0, c, s, 0, 0, -s, c, 0, 0, 0, 0, 1};
    }

    public static float[] rotationY(float rad) {
        float c = (float) Math.cos(rad), s = (float) Math.sin(rad);
        return new float[]{c, 0, -s, 0, 0, 1, 0, 0, s, 0, c, 0, 0, 0, 0, 1};
    }

    public static float[] rotationZ(float rad) {
        float c = (float) Math.cos(rad), s = (float) Math.sin(rad);
        return new float[]{c, s, 0, 0, -s, c, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    /** three.js's Euler order "XYZ" in degrees: Rx * Ry * Rz. */
    public static float[] eulerXYZDegrees(float x, float y, float z) {
        float d = (float) (Math.PI / 180);
        return mul(rotationX(x * d), mul(rotationY(y * d), rotationZ(z * d)));
    }

    /** m applied to the point (x, y, z), written to out at off. */
    public static void point(float[] m, float x, float y, float z, float[] out, int off) {
        out[off] = m[0] * x + m[4] * y + m[8] * z + m[12];
        out[off + 1] = m[1] * x + m[5] * y + m[9] * z + m[13];
        out[off + 2] = m[2] * x + m[6] * y + m[10] * z + m[14];
    }

    /** m's 3x3 part applied to the direction (x, y, z), normalised, written to out at off. */
    public static void direction(float[] m, float x, float y, float z, float[] out, int off) {
        float a = m[0] * x + m[4] * y + m[8] * z;
        float b = m[1] * x + m[5] * y + m[9] * z;
        float c = m[2] * x + m[6] * y + m[10] * z;
        float l = (float) Math.sqrt(a * a + b * b + c * c);
        if (l > 1e-8f) {
            a /= l;
            b /= l;
            c /= l;
        }
        out[off] = a;
        out[off + 1] = b;
        out[off + 2] = c;
    }
}
