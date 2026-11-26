package dev.breeze.recorder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The camera's turn at any moment of a recording, for watching and
 * exporting: from the frames the player saw (CAMERA records, one per frame
 * with its time), so a video at 240 fps turns exactly as the player turned,
 * fast flicks included, instead of being smoothed between ticks. Where a
 * part of the recording has no frames (recordings made before 1.2.3), the
 * tick states (SELF records, 20 a second) are used the same way.
 *
 * Between two samples the turn is interpolated; across a gap longer than
 * {@link #HOLD_GAP_MS} (the game hung, or was paused) the earlier one is
 * held, as the player saw it. Only the chunks around the moment asked for
 * are read (the one holding it and its neighbours), so memory stays small
 * whatever the recording's length. Not thread safe: the render thread only.
 */
public final class CameraTrack {

    public static final double HOLD_GAP_MS = 250;

    /** One chunk's samples, by time. */
    private record Samples(double[] t, float[] yaw, float[] pitch, int n, boolean frames) {}

    private final RecReader reader;
    private final Map<Integer, Samples> cache = new HashMap<>();
    private int loaded = -1;
    private double[] t = new double[0];
    private float[] yaw = new float[0];
    private float[] pitch = new float[0];
    private int n;
    private boolean frames;

    public CameraTrack(RecReader reader) {
        this.reader = reader;
    }

    /** Whether the samples around the last moment asked for came from frames (not ticks). */
    public boolean fromFrames() {
        return frames;
    }

    /**
     * The camera at ms into out (out[0] yaw, out[1] pitch). False when the
     * recording has no camera near there (nothing is written to out).
     */
    public boolean at(double ms, float[] out) {
        List<RecReader.ChunkRef> chunks = reader.chunks();
        if (chunks.isEmpty()) return false;
        int c = reader.chunkAt((long) Math.floor(ms));
        if (c != loaded) load(c);
        if (n == 0) return false;
        int lo = 0, hi = n - 1, i = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (t[mid] <= ms) {
                i = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (i < 0) {
            out[0] = yaw[0];
            out[1] = pitch[0];
        } else if (i >= n - 1 || t[i + 1] - t[i] > HOLD_GAP_MS || t[i + 1] <= t[i]) {
            out[0] = yaw[i];
            out[1] = pitch[i];
        } else {
            float f = (float) ((ms - t[i]) / (t[i + 1] - t[i]));
            out[0] = yaw[i] + (yaw[i + 1] - yaw[i]) * f;
            out[1] = pitch[i] + (pitch[i + 1] - pitch[i]) * f;
        }
        return true;
    }

    /** The samples of chunk c and its neighbours, joined in time order. */
    private void load(int c) {
        loaded = c;
        Samples[] parts = {chunk(c - 1), chunk(c), chunk(c + 1)};
        int total = 0;
        for (Samples s : parts) if (s != null) total += s.n();
        if (t.length < total) {
            t = new double[total];
            yaw = new float[total];
            pitch = new float[total];
        }
        n = 0;
        for (Samples s : parts) {
            if (s == null) continue;
            System.arraycopy(s.t(), 0, t, n, s.n());
            System.arraycopy(s.yaw(), 0, yaw, n, s.n());
            System.arraycopy(s.pitch(), 0, pitch, n, s.n());
            n += s.n();
        }
        Samples mid = parts[1];
        frames = mid != null && mid.frames();
        cache.keySet().removeIf(k -> k < c - 1 || k > c + 1);
    }

    private Samples chunk(int i) {
        if (i < 0 || i >= reader.chunks().size()) return null;
        Samples s = cache.get(i);
        if (s != null) return s;
        s = read(i);
        cache.put(i, s);
        return s;
    }

    private Samples read(int i) {
        List<RecReader.Rec> recs;
        try {
            recs = reader.readChunk(reader.chunks().get(i));
        } catch (Exception e) {
            return new Samples(new double[0], new float[0], new float[0], 0, false);
        }
        int cams = 0, selfs = 0;
        for (RecReader.Rec r : recs) {
            if (r.kind() == RecFormat.Kind.CAMERA && r.data().length >= CameraFrames.SIZE) cams++;
            else if (r.kind() == RecFormat.Kind.SELF && r.data().length >= CameraFrames.SELF_MIN) selfs++;
        }
        boolean useFrames = cams > 0;
        int count = useFrames ? cams : selfs;
        double[] tt = new double[count];
        float[] yy = new float[count], pp = new float[count];
        int k = 0;
        for (RecReader.Rec r : recs) {
            byte[] d = r.data();
            if (useFrames) {
                if (r.kind() != RecFormat.Kind.CAMERA || d.length < CameraFrames.SIZE) continue;
                tt[k] = r.timeMs() + CameraFrames.micros(d) / 1000.0;
                yy[k] = CameraFrames.yaw(d);
                pp[k] = CameraFrames.pitch(d);
            } else {
                if (r.kind() != RecFormat.Kind.SELF || d.length < CameraFrames.SELF_MIN) continue;
                tt[k] = r.timeMs();
                yy[k] = CameraFrames.getFloat(d, CameraFrames.SELF_YAW);
                pp[k] = CameraFrames.getFloat(d, CameraFrames.SELF_PITCH);
            }
            if (Float.isNaN(yy[k]) || Float.isNaN(pp[k])) continue;
            k++;
        }
        return new Samples(tt, yy, pp, k, useFrames);
    }
}
