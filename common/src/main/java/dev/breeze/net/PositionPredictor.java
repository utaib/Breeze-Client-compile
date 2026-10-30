package dev.breeze.net;

/**
 * Where another player is on the server now (Ping Ghost), worked out from the
 * last positions the server sent and how long they took to arrive.
 *
 * The game draws other players where the server said they were, smoothed over
 * a few ticks, so what is drawn is late by the trip from the server (half the
 * ping) plus the smoothing. Here the last position the server sent is moved on
 * along the player's recent velocity by the time since it arrived plus a lead:
 * half the ping for "where they are on the server now", the whole ping for
 * "where they will be when your hit arrives".
 *
 * Fed every frame with the latest server position ({@link #observe}); only a
 * change counts as a new sample, stamped with the time it was first seen.
 */
public final class PositionPredictor {

    /** No new position for this long means the player stopped: no velocity. */
    public static final long STILL_MS = 250;
    /** Never predict further ahead than this. */
    public static final long MAX_LEAD_MS = 600;
    /** A move longer than this between samples is a teleport, not motion. */
    public static final double TELEPORT_BLOCKS = 8.0;

    private double x, y, z;
    private long seenAt = -1;
    private double vx, vy, vz; // blocks per millisecond

    /** The latest position the server sent, seen at {@code nowMs}. */
    public void observe(long nowMs, double nx, double ny, double nz) {
        if (seenAt < 0) {
            set(nowMs, nx, ny, nz);
            return;
        }
        if (nx == x && ny == y && nz == z) return;
        long dt = nowMs - seenAt;
        double dx = nx - x, dy = ny - y, dz = nz - z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dt <= 0 || dt > STILL_MS || dist > TELEPORT_BLOCKS) {
            // A first move after standing still, or a jump across the map:
            // there is no motion to carry on yet.
            vx = vy = vz = 0;
        } else {
            double ix = dx / dt, iy = dy / dt, iz = dz / dt;
            // Smoothed: samples arrive with the frame rate's jitter.
            vx = 0.5 * vx + 0.5 * ix;
            vy = 0.5 * vy + 0.5 * iy;
            vz = 0.5 * vz + 0.5 * iz;
        }
        x = nx;
        y = ny;
        z = nz;
        seenAt = nowMs;
    }

    /** Whether anything has been observed. */
    public boolean ready() {
        return seenAt >= 0;
    }

    /** The predicted position at {@code nowMs} with {@code leadMs} of lead, as {x, y, z}. */
    public double[] predict(long nowMs, long leadMs) {
        long age = Math.max(0, nowMs - seenAt);
        if (seenAt < 0 || age > STILL_MS) return new double[] {x, y, z};
        long ahead = Math.max(0, Math.min(MAX_LEAD_MS, age + Math.max(0, leadMs)));
        return new double[] {x + vx * ahead, y + vy * ahead, z + vz * ahead};
    }

    /** Speed in blocks per second, for tests and the self-test log. */
    public double speed() {
        return Math.sqrt(vx * vx + vy * vy + vz * vz) * 1000.0;
    }

    private void set(long now, double nx, double ny, double nz) {
        x = nx;
        y = ny;
        z = nz;
        seenAt = now;
        vx = vy = vz = 0;
    }
}
