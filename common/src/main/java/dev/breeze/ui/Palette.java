package dev.breeze.ui;

/**
 * The Breeze launcher's design tokens, ported verbatim into the mod.
 *
 * The mod previously used a purple/blue pair (#9B5CFF / #4F8CFF) on a #14141C
 * panel, which appears nowhere in the launcher. The launcher uses a blue accent
 * on a neutral surface ladder. That mismatch is a large part of why the in-game
 * UI does not read as the same product.
 *
 * These values are copied from BreezeV2.css, not approximated:
 *
 * <pre>
 *   --bg-deep        #0e1017      --accent          #78b2ff
 *   --bg             #12151d      --border          rgba(255,255,255,0.06)
 *   --surface        #191d27      --border-hover    rgba(255,255,255,0.11)
 *   --surface-hover  #20242f      --text-primary    #e7e9ee
 *   --surface-active #272c38      --text-secondary  #a6adba
 *                                 --text-faint      #666d7a
 * </pre>
 *
 * The surface ladder is the important idea and the mod was missing it entirely:
 * elevation comes from luminance, so a raised element is *lighter* than what it
 * sits on. Panels drawn as one flat dark rectangle read as a game overlay;
 * a ladder reads as a desktop application.
 *
 * Colours are ARGB ints. Alpha is baked in where an element is meant to be
 * translucent, so callers never have to remember to add it.
 */
public final class Palette {
    private Palette() {}

    // Backgrounds, darkest to lightest. Never invert this order.
    public static final int BG_DEEP = 0xFF0E1017;
    public static final int BG = 0xFF12151D;
    public static final int SURFACE = 0xFF191D27;
    public static final int SURFACE_HOVER = 0xFF20242F;
    public static final int SURFACE_ACTIVE = 0xFF272C38;

    // Hairline borders. Low alpha over whatever sits behind them.
    public static final int BORDER = 0x0FFFFFFF;
    public static final int BORDER_HOVER = 0x1CFFFFFF;

    // Text. Three levels only; more than three stops reading as a hierarchy.
    public static final int TEXT_PRIMARY = 0xFFE7E9EE;
    public static final int TEXT_SECONDARY = 0xFFA6ADBA;
    public static final int TEXT_FAINT = 0xFF666D7A;

    /** One accent, used sparingly. Two accents means neither is the accent. */
    public static final int ACCENT = 0xFF78B2FF;
    public static final int ACCENT_DIM = 0x2278B2FF;

    public static final int OK = 0xFF4ADE80;
    public static final int WARN = 0xFFFBBF24;
    public static final int ERROR = 0xFFF87171;

    /**
     * Scrim behind a modal panel. Dark enough to push the game back without
     * hiding it, matching the launcher's overlay weight.
     */
    public static final int SCRIM = 0xB3000000;

    /** Drop shadow under a raised panel. Applied as a few stacked bands. */
    public static final int SHADOW = 0x40000000;

    /** Replace a colour's alpha, keeping its RGB. */
    public static int alpha(int argb, int a) {
        return ((a & 0xFF) << 24) | (argb & 0x00FFFFFF);
    }

    /** Blend two ARGB colours. t=0 returns a, t=1 returns b. */
    public static int lerp(int a, int b, float t) {
        float k = t < 0 ? 0 : t > 1 ? 1 : t;
        int aa = (a >>> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int ra = (int) (aa + (ba - aa) * k);
        int rr = (int) (ar + (br - ar) * k);
        int rg = (int) (ag + (bg - ag) * k);
        int rb = (int) (ab + (bb - ab) * k);
        return (ra << 24) | (rr << 16) | (rg << 8) | rb;
    }

    /** The surface a row should use for its current interaction state. */
    public static int surfaceFor(boolean hovered, boolean active) {
        if (active) return SURFACE_ACTIVE;
        if (hovered) return SURFACE_HOVER;
        return SURFACE;
    }
}
