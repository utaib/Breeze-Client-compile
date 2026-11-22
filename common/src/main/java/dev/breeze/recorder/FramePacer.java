package dev.breeze.recorder;

import java.util.Locale;

/**
 * Turns the game's uneven frames (54 to 70 a second on a laptop) into a
 * video at a constant frame rate, by the time each frame was really shown.
 *
 * The video's frames are slots on a fixed clock: slot k is at
 * {@code t0 + k / fps}. A frame shown at time t belongs to the slot nearest
 * to t. The first frame in a slot fills it; a later frame in the same slot
 * is not needed (it is never read back, which saves the graphics card's
 * time); a slot no frame reached shows the frame before it again, as a
 * screen refreshing at the video's rate would. So every frame appears at
 * the moment it was shown, give or take half a slot, and the video's length
 * is the recording's length, frame for frame with the sound.
 *
 * Not thread safe: the render thread plans and commits each frame.
 */
public final class FramePacer {

    private final int fps;
    private final double period;
    private long t0 = -1;
    private long lastSlot = -1;
    private long lastShown;

    // Statistics.
    private long offered, used, skipped, dupSlots, dropped;
    private long lastOffered = -1;
    private double gapSum, gapSq, gapMax;
    private long gaps;
    private double errSum, errMax;
    private double jitSum, jitSq;
    private long jits;

    public FramePacer(int fps) {
        this.fps = Math.max(1, fps);
        this.period = 1e9 / this.fps;
    }

    public int fps() {
        return fps;
    }

    /** The slot a frame shown at t falls in (the nearest). */
    long slot(long t) {
        return Math.round((t - t0) / period);
    }

    /**
     * Whether the frame shown at t (System.nanoTime) goes into the video:
     * -1 when it does not (its slot is filled already), else how many slots
     * before it repeat the frame before (0 when it follows directly). Also
     * counts the frame for the evenness figures.
     */
    public int plan(long t) {
        offered++;
        if (lastOffered >= 0) {
            double g = t - lastOffered;
            gapSum += g;
            gapSq += g * g;
            gapMax = Math.max(gapMax, g);
            gaps++;
        }
        lastOffered = t;
        if (t0 < 0) return 0;
        long s = slot(t);
        if (s <= lastSlot) {
            skipped++;
            return -1;
        }
        return (int) Math.min(Integer.MAX_VALUE, s - lastSlot - 1);
    }

    /** The frame planned at t is on its way to the video (read back and queued). */
    public void commit(long t) {
        if (t0 < 0) {
            t0 = t;
            lastSlot = 0;
            lastShown = t;
            used++;
            return;
        }
        long s = slot(t);
        long dups = s - lastSlot - 1;
        dupSlots += dups;
        // Motion between consecutive video frames, against one slot each.
        for (long i = 0; i < Math.min(dups, 1000); i++) jitter(-period);
        jitter((t - lastShown) - period);
        double err = Math.abs(t - (t0 + s * period));
        errSum += err;
        errMax = Math.max(errMax, err);
        lastSlot = s;
        lastShown = t;
        used++;
    }

    /** A planned frame that could not be taken (the graphics card or encoder was behind): its slot is filled later. */
    public void dropped() {
        dropped++;
    }

    private void jitter(double d) {
        jitSum += d;
        jitSq += d * d;
        jits++;
    }

    /** Slots after the last frame up to the end at tEnd: the last frame repeats there, so the video lasts as long as the recording. */
    public int trailing(long tEnd) {
        if (t0 < 0) return 0;
        long total = Math.round((tEnd - t0) / period);
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, total - (lastSlot + 1)));
    }

    /** Video frames so far (slots filled, repeats included). */
    public long slots() {
        return t0 < 0 ? 0 : lastSlot + 1;
    }

    public long offered() { return offered; }
    public long used() { return used; }
    public long skipped() { return skipped; }
    public long dupSlots() { return dupSlots; }
    public long droppedFrames() { return dropped; }

    /** Mean gap between the game's frames, ms. */
    public double gameFrameMs() {
        return gaps == 0 ? 0 : gapSum / gaps / 1e6;
    }

    /** How uneven the game's frames were: standard deviation of the gaps, ms. */
    public double gameFrameSdMs() {
        if (gaps < 2) return 0;
        double m = gapSum / gaps;
        return Math.sqrt(Math.max(0, gapSq / gaps - m * m)) / 1e6;
    }

    /**
     * How even the video's motion is: the standard deviation, in ms, of the
     * time between the moments two consecutive video frames show, against
     * one slot. 0 is perfectly even; a repeated or skipped frame adds a slot.
     */
    public double motionJitterMs() {
        if (jits == 0) return 0;
        double m = jitSum / jits;
        return Math.sqrt(Math.max(0, jitSq / jits - m * m)) / 1e6;
    }

    /** One line for the log and the self-test. */
    public String summary() {
        return String.format(Locale.ROOT,
                "fps=%d slots=%d gameFrames=%d used=%d skipped=%d repeatedSlots=%d dropped=%d gameFrameMs=%.2f gameFrameSdMs=%.2f gameFrameMaxMs=%.1f slotErrorMeanMs=%.2f slotErrorMaxMs=%.2f motionJitterMs=%.2f",
                fps, slots(), offered, used, skipped, dupSlots, dropped, gameFrameMs(), gameFrameSdMs(), gapMax / 1e6,
                used > 1 ? errSum / (used - 1) / 1e6 : 0, errMax / 1e6, motionJitterMs());
    }
}
