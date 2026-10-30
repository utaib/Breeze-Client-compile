package dev.breeze.compat;

import dev.breeze.ui.TagArt;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;

/**
 * The characters a Breeze tag is drawn with: Breeze's Wind Charge pictures,
 * one per tag colour (common TagArt), which the mod adds to Minecraft's
 * default and uniform fonts (assets/minecraft/font, sheet
 * assets/breeze/textures/font/tags.png) at private-use code points no other
 * text uses.
 */
public final class TagGlyph {

    /** The plain Wind Charge, for a check that needs one character. */
    public static final String ICON = TagArt.PLAIN.glyph();
    /** Each picture's width in the default font: 12 pixels at 0.8 (8 high from 10), and 1 of spacing. */
    public static final int WIDTH = 11;
    /** The pictures carry their own colours, so they are drawn in white (a colour would tint them). */
    public static final boolean OWN_COLOURS = true;

    private TagGlyph() {}

    /** The Wind Charge for a tag colour, 0xRRGGBB. */
    public static String forColour(int rgb) {
        return TagArt.of(rgb).glyph();
    }

    /** A picture's character as text, in {@code tint}; the default font has it. */
    public static Component label(String glyph, int tint) {
        return Component.literal(glyph).withStyle(s -> s.withColor(TextColor.fromRgb(tint & 0xFFFFFF)));
    }
}
