package dev.breeze.recorder;

/**
 * The camera of each frame the player saw while recording: a CAMERA record
 * of {@link #SIZE} bytes, made without allocating (the caller's buffer is
 * reused every frame).
 *
 * <pre>
 * u16 microseconds past the record's millisecond (0 to 999)
 * f32 yaw (yRot, degrees, not wrapped)
 * f32 pitch (xRot, degrees)
 * </pre>
 *
 * Big-endian, like the rest of the file. The record's own time gives the
 * millisecond, so a frame's time is known to the microsecond, which keeps
 * the order of frames 2 ms apart (a game at 500 fps) and their spacing.
 */
public final class CameraFrames {

    private CameraFrames() {}

    public static final int SIZE = 2 + 4 + 4;

    /** Where a SELF record (RecSelf) keeps the tick's yaw and pitch: after its format byte and three doubles. */
    static final int SELF_YAW = 1 + 3 * 8, SELF_PITCH = SELF_YAW + 4, SELF_MIN = SELF_PITCH + 4;

    /** Writes one frame into buf (at least SIZE bytes). */
    public static void encode(byte[] buf, int micros, float yaw, float pitch) {
        int m = Math.max(0, Math.min(999, micros));
        buf[0] = (byte) (m >>> 8);
        buf[1] = (byte) m;
        putFloat(buf, 2, yaw);
        putFloat(buf, 6, pitch);
    }

    /** The microseconds past the record's millisecond, or -1 when the record is not a camera frame. */
    public static int micros(byte[] d) {
        if (d.length < SIZE) return -1;
        return Math.min(999, (d[0] & 0xFF) << 8 | (d[1] & 0xFF));
    }

    public static float yaw(byte[] d) {
        return getFloat(d, 2);
    }

    public static float pitch(byte[] d) {
        return getFloat(d, 6);
    }

    static void putFloat(byte[] b, int at, float f) {
        int v = Float.floatToRawIntBits(f);
        b[at] = (byte) (v >>> 24);
        b[at + 1] = (byte) (v >>> 16);
        b[at + 2] = (byte) (v >>> 8);
        b[at + 3] = (byte) v;
    }

    static float getFloat(byte[] b, int at) {
        return Float.intBitsToFloat((b[at] & 0xFF) << 24 | (b[at + 1] & 0xFF) << 16 | (b[at + 2] & 0xFF) << 8 | (b[at + 3] & 0xFF));
    }
}
