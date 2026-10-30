package dev.breeze;

public final class TpsTracker {

    private static long last;
    private static double tps = 20.0;

    private TpsTracker() {}

    public static void onTimeUpdate() {
        long now = System.currentTimeMillis();
        if (last != 0) {
            long dt = now - last;
            if (dt > 0) {
                double current = Math.min(20.0, 20000.0 / dt);
                tps = tps * 0.7 + current * 0.3;
            }
        }
        last = now;
    }

    public static double tps() {
        return tps;
    }
}
