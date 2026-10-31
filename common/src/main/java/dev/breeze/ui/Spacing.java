package dev.breeze.ui;

/**
 * One spacing scale for the whole client.
 *
 * The screens previously used 6, 8, 9, 10, 12, 14, 16, 20, 22, 26, 28, 34, 36
 * and 52 as padding values with no relationship between them, which is the
 * mechanical reason the UI reads as "assembled from independent components"
 * rather than designed. These are a 4px scale: every gap is a multiple of 4, so
 * things line up even when nobody is thinking about it.
 *
 * Named by role, not by size, so a value can be retuned in one place without
 * hunting for every literal that happened to be the same number.
 */
public final class Spacing {
    private Spacing() {}

    /** The 4px base unit. Every other value here is a multiple of it. */
    public static final int UNIT = 4;

    public static final int XS = UNIT;          // 4  hairline gaps, icon padding
    public static final int SM = UNIT * 2;      // 8  between related controls
    public static final int MD = UNIT * 3;      // 12 standard padding inside a panel
    public static final int LG = UNIT * 4;      // 16 between groups
    public static final int XL = UNIT * 6;      // 24 between sections

    /** Gap between a screen edge and any panel. */
    public static final int SCREEN_MARGIN = UNIT * 5;   // 20

    /** Padding inside a panel before its content starts. */
    public static final int PANEL = MD;                 // 12

    /** Height of a panel's title bar. */
    public static final int HEADER_H = UNIT * 7;        // 28

    /** Height of a panel's action row. */
    public static final int FOOTER_H = UNIT * 8;        // 32

    /** A single row in a list: one line of text plus breathing room. */
    public static final int ROW_H = UNIT * 5;           // 20

    /** A sidebar tab. Taller than a list row because it is a primary target. */
    public static final int TAB_H = UNIT * 6;           // 24

    /** Minimum square hit area. Anything smaller is hard to click at GUI scale 1. */
    public static final int HIT = UNIT * 5;             // 20

    public static final int CORNER = UNIT;              // 4  corner radius
}
