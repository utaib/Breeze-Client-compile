package dev.breeze.hud;

/**
 * Fixed-decimal number formatting for the HUD.
 *
 * These replace {@code String.format("%.1f", v)} in the per-frame draw path.
 * String.format parses the format string, allocates a Formatter, a StringBuilder
 * and a boxed argument array on every call, and runs a general-purpose
 * conversion for what is always the same shape. Thirteen HUD modules were doing
 * that once or more per frame.
 *
 * Not a general replacement: these round half-up on the absolute value and take
 * no locale, which is correct for coordinates, speeds and hearts and wrong for
 * anything a user would expect localised.
 */
public final class Fmt {

    private Fmt() {}

    /**
     * Zero decimals.
     *
     * Rounds the magnitude and reapplies the sign rather than calling
     * Math.round on the signed value. Math.round rounds halves toward positive
     * infinity, so it turns -1000000.5 into -1000000 where %.0f gives -1000001.
     * That is a visible disagreement on any HUD showing a negative coordinate,
     * and it would only show up at exact halves, which is the hardest kind of
     * difference to notice in testing.
     *
     * The one intentional difference from %.0f: a value that rounds to zero
     * prints "0", never "-0".
     */
    public static String d0(double v) {
        if (!finite(v)) return "0";
        long s = Math.round(Math.abs(v));
        return (v < 0 && s != 0 ? "-" : "") + s;
    }

    /** One decimal, e.g. "12.4". */
    public static String d1(double v) {
        if (!finite(v)) return "0.0";
        long s = Math.round(Math.abs(v) * 10.0);
        return (v < 0 && s != 0 ? "-" : "") + (s / 10) + "." + (s % 10);
    }

    /** Two decimals, e.g. "12.40". Zero-padded, so 12.4 is not shown as "12.4". */
    public static String d2(double v) {
        if (!finite(v)) return "0.00";
        long s = Math.round(Math.abs(v) * 100.0);
        long frac = s % 100;
        return (v < 0 && s != 0 ? "-" : "") + (s / 100) + "." + (frac < 10 ? "0" : "") + frac;
    }

    /**
     * NaN and the infinities have to be caught here rather than left to
     * Math.round, which maps NaN to 0 and the infinities to Long.MIN/MAX_VALUE.
     * A reach display briefly reading -9223372036854775808 is worse than 0.
     */
    private static boolean finite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }
}
