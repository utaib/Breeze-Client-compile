package dev.breeze.compat;

/**
 * Minecraft 1.17 only: the tag is a star (U+2726) from Minecraft's own font,
 * drawn in the tag's colour.
 *
 * On 1.17 the Fabric API that exists for it (0.36.0) serves the mods'
 * resources as one pack, and a mod's minecraft:font/default.json there takes
 * the place of Minecraft's instead of adding to it: with the Wind Charge
 * glyph file every text in the game drew as empty boxes (seen from 2.10.0 in
 * every 1.17 screenshot; 1.17.1 and later add to the font as they should).
 * So the 1.17 build leaves Minecraft's font alone (versions/1.17/removed.txt)
 * and uses a character the font already has.
 */
public final class TagGlyph {

    public static final String ICON = "✦";
    /** Any drawn width: the star's comes from Minecraft's font, not Breeze's. */
    public static final int WIDTH = -1;
    /** The star is plain white in the font, so the tag's colour is what tells tags apart. */
    public static final boolean OWN_COLOURS = false;

    private TagGlyph() {}

    public static String forColour(int rgb) {
        return ICON;
    }
}
