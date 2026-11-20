package dev.breeze.recorder;

/**
 * The clock of a recording being watched: where it is, whether it plays and
 * how fast. Watching advances it by real time; exporting advances it by one
 * video frame at a time ({@link #stepFrame}), whatever the computer's frame
 * rate, so the video is smooth even when the game draws slowly.
 */
public final class Timeline {

    public static final double[] SPEEDS = {0.1, 0.25, 0.5, 1, 2, 4, 8};

    private final long durationMs;
    private double positionMs;
    private boolean playing;
    private int speedIndex = 3;

    public Timeline(long durationMs) {
        this.durationMs = Math.max(0, durationMs);
    }

    public long durationMs() {
        return durationMs;
    }

    public long positionMs() {
        return (long) positionMs;
    }

    /** The exact position, with fractions of a millisecond (for frames between ticks). */
    public double exactMs() {
        return positionMs;
    }

    public boolean playing() {
        return playing;
    }

    public double speed() {
        return SPEEDS[speedIndex];
    }

    public void play() {
        if (positionMs >= durationMs) positionMs = 0;
        playing = true;
    }

    public void pause() {
        playing = false;
    }

    public void toggle() {
        if (playing) pause();
        else play();
    }

    /** One step faster (by > 0) or slower (by < 0) through {@link #SPEEDS}. */
    public void changeSpeed(int by) {
        speedIndex = Math.max(0, Math.min(SPEEDS.length - 1, speedIndex + by));
    }

    public void setSpeed(double speed) {
        int best = 0;
        for (int i = 0; i < SPEEDS.length; i++) {
            if (Math.abs(SPEEDS[i] - speed) < Math.abs(SPEEDS[best] - speed)) best = i;
        }
        speedIndex = best;
    }

    /** Jumps to a moment (clamped to the recording). */
    public void seek(double ms) {
        positionMs = Math.max(0, Math.min(durationMs, ms));
    }

    /** Moves on by realMs of real time at the current speed; stops at the end. Returns the new position. */
    public long advance(double realMs) {
        if (!playing || realMs <= 0) return positionMs();
        positionMs = Math.min(durationMs, positionMs + realMs * speed());
        if (positionMs >= durationMs) playing = false;
        return positionMs();
    }

    /** Moves on by exactly one frame of a video at fps (exporting), ignoring speed. */
    public double stepFrame(int fps) {
        // From the frame number, not by adding 1000/fps each time: sums of
        // 16.666... drift, and the video would gain or lose a frame.
        long frame = Math.round(positionMs * fps / 1000.0) + 1;
        positionMs = Math.min(durationMs, frame * 1000.0 / fps);
        return positionMs;
    }

    public boolean atEnd() {
        return positionMs >= durationMs;
    }

    /** "1:05" or "1:02:03" for a time in ms. */
    public static String clock(long ms) {
        long s = Math.max(0, ms) / 1000;
        long h = s / 3600, m = (s / 60) % 60, sec = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%d:%02d", m, sec);
    }

    /** How many frames a video of durationMs at fps has. */
    public static long frames(long durationMs, int fps) {
        return Math.max(1, (long) Math.ceil(durationMs * fps / 1000.0));
    }
}
