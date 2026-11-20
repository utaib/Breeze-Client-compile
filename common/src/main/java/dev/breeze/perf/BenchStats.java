package dev.breeze.perf;

import java.util.Arrays;
import java.util.Locale;

/**
 * What a run of the in-game benchmark measured, from the time each frame
 * took. The average is frames over time. The "1% low" and "0.1% low" are the
 * frame rate of the slowest hundredth and thousandth of the frames (their
 * count over their time): the stutters a player feels, which an average
 * hides.
 */
public final class BenchStats {

    public final int frames;
    public final double seconds;
    public final double avgFps;
    public final double low1Fps;
    public final double low01Fps;
    public final double minFps;
    public final double maxFps;
    public final double medianMs;

    private BenchStats(int frames, double seconds, double avgFps, double low1Fps, double low01Fps,
                       double minFps, double maxFps, double medianMs) {
        this.frames = frames;
        this.seconds = seconds;
        this.avgFps = avgFps;
        this.low1Fps = low1Fps;
        this.low01Fps = low01Fps;
        this.minFps = minFps;
        this.maxFps = maxFps;
        this.medianMs = medianMs;
    }

    /** From the first count frame times (nanoseconds each); null when there are none. */
    public static BenchStats of(long[] frameNanos, int count) {
        if (frameNanos == null || count <= 0) return null;
        count = Math.min(count, frameNanos.length);
        long[] sorted = Arrays.copyOf(frameNanos, count);
        Arrays.sort(sorted);
        long total = 0;
        for (long ns : sorted) total += Math.max(1, ns);
        double seconds = total / 1e9;
        return new BenchStats(count, seconds, count / seconds,
                slowest(sorted, 0.01), slowest(sorted, 0.001),
                1e9 / Math.max(1, sorted[count - 1]), 1e9 / Math.max(1, sorted[0]),
                sorted[count / 2] / 1e6);
    }

    /** The frame rate of the slowest share of the frames (at least one frame). */
    private static double slowest(long[] sorted, double share) {
        int n = Math.max(1, (int) Math.ceil(sorted.length * share));
        long sum = 0;
        for (int i = sorted.length - n; i < sorted.length; i++) sum += Math.max(1, sorted[i]);
        return n / (sum / 1e9);
    }

    /** "243 fps average, 1% low 121, 0.1% low 88". */
    public String summary() {
        return whole(avgFps) + " fps average, 1% low " + whole(low1Fps) + ", 0.1% low " + whole(low01Fps);
    }

    public static String whole(double v) {
        return String.valueOf(Math.round(v));
    }

    static String one(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    /** The numbers as the fields of a JSON object, without the braces. */
    public String jsonFields() {
        return "\"frames\":" + frames
                + ",\"seconds\":" + one(seconds)
                + ",\"avgFps\":" + one(avgFps)
                + ",\"low1Fps\":" + one(low1Fps)
                + ",\"low01Fps\":" + one(low01Fps)
                + ",\"minFps\":" + one(minFps)
                + ",\"maxFps\":" + one(maxFps)
                + ",\"medianMs\":" + String.format(Locale.ROOT, "%.2f", medianMs);
    }

    /**
     * How this run compares with an earlier average, as "12% faster than the
     * run before (217 fps)"; empty when there is nothing to compare with or
     * the two are within 1%.
     */
    public String against(double earlierAvgFps) {
        if (!(earlierAvgFps > 0)) return "";
        double change = (avgFps - earlierAvgFps) / earlierAvgFps * 100;
        if (Math.abs(change) < 1) return "the same as the run before (" + whole(earlierAvgFps) + " fps)";
        return Math.round(Math.abs(change)) + "% " + (change > 0 ? "faster" : "slower")
                + " than the run before (" + whole(earlierAvgFps) + " fps)";
    }
}
