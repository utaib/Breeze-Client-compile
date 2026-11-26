package dev.breeze.recorder;

/**
 * Turns the game's uneven frames into a video's even ones (the screen
 * video). The game shows a frame whenever it is drawn, 54 to 70 times a
 * second on a laptop; a 60 fps video needs one exactly every 1/60 s. Each
 * frame is stamped with the moment it was shown, and every tick of the
 * video takes the frame shown nearest to it: a frame between two ticks
 * appears once, a frame that stayed on screen across two ticks appears
 * twice, and one replaced before the next tick does not appear. That is
 * what a viewer of the screen saw at each tick, so the video moves as
 * smoothly as the game looked, and its length is the real time recorded.
 *
 * The pacer holds one frame (the newest) until the next one tells how long
 * it stayed; {@link #add} returns how many times the held frame goes into
 * the video. Not thread safe: the render thread only.
 */
public final class FramePacer {

    private final long tickNanos;
    private long nextTick;
    private long heldAt = Long.MIN_VALUE;
    private boolean started;

    // Evenness, for the log and the self-test.
    private long frames, ticks, repeated, skipped;
    private long lastAt;
    private double sumGap, sumGap2;
    private long worstGap;
    private double sumError;
    private long worstError;

    public FramePacer(int fps) {
        this.tickNanos = 1_000_000_000L / Math.max(1, fps);
    }

    /**
     * A frame shown at {@code nanos} (System.nanoTime, never earlier than the
     * last). Returns how many video ticks the frame held before it takes:
     * 0 or more (0 the first time).
     */
    public int add(long nanos) {
        frames++;
        if (!started) {
            started = true;
            nextTick = nanos;
            heldAt = nanos;
            lastAt = nanos;
            return 0;
        }
        long gap = Math.max(0, nanos - lastAt);
        lastAt = nanos;
        sumGap += gap;
        sumGap2 += (double) gap * gap;
        if (gap > worstGap) worstGap = gap;
        long mid = heldAt + (nanos - heldAt) / 2;
        int n = take(mid);
        heldAt = nanos;
        return n;
    }

    /** At the end: how many ticks the held frame takes (until half a tick after it was shown). 0 before any frame. */
    public int finish() {
        if (!started) return 0;
        return take(heldAt + tickNanos / 2);
    }

    /** The held frame takes every tick up to {@code until}. */
    private int take(long until) {
        int n = 0;
        while (nextTick <= until) {
            long err = Math.abs(nextTick - heldAt);
            sumError += err;
            if (err > worstError) worstError = err;
            nextTick += tickNanos;
            n++;
        }
        ticks += n;
        if (n == 0) skipped++;
        else if (n > 1) repeated += n - 1;
        return n;
    }

    public long frames() {
        return frames;
    }

    /** Video frames made so far. */
    public long ticks() {
        return ticks;
    }

    /** Game frames that did not reach the video (shown between two ticks). */
    public long skipped() {
        return skipped;
    }

    /** Video frames that repeat the one before (the game showed no new frame for that tick). */
    public long repeated() {
        return repeated;
    }

    /** The game's frame-to-frame time: average and spread (standard deviation) and worst, in milliseconds. */
    public double gapAvgMs() {
        long n = Math.max(1, frames - 1);
        return sumGap / n / 1e6;
    }

    public double gapStdMs() {
        long n = Math.max(1, frames - 1);
        double mean = sumGap / n;
        return Math.sqrt(Math.max(0, sumGap2 / n - mean * mean)) / 1e6;
    }

    public double gapWorstMs() {
        return worstGap / 1e6;
    }

    /** How far each video frame's picture is from its tick in time: average and worst, in milliseconds. */
    public double errorAvgMs() {
        return ticks == 0 ? 0 : sumError / ticks / 1e6;
    }

    public double errorWorstMs() {
        return worstError / 1e6;
    }

    /** One line for the log and the self-test. */
    public String summary() {
        return String.format(java.util.Locale.ROOT,
                "gameFrames=%d videoFrames=%d repeated=%d skipped=%d gameGapMs=%.2f gameGapStdMs=%.2f gameGapWorstMs=%.1f tickErrorAvgMs=%.2f tickErrorWorstMs=%.2f",
                frames, ticks, repeated, skipped, gapAvgMs(), gapStdMs(), gapWorstMs(), errorAvgMs(), errorWorstMs());
    }
}
