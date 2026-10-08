package dev.breeze.compat;

import dev.breeze.ui.TagArt;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceLocation;

/**
 * Minecraft 1.17 only: the Wind Charge pictures in Breeze's own font,
 * {@code breeze:tags} (assets/breeze/font/tags.json, the same sheet as the
 * other versions').
 *
 * On 1.17 the Fabric API that exists for it (0.36.0) serves the mods'
 * resources as one pack, and a mod's minecraft:font/default.json there takes
 * the place of Minecraft's instead of adding to it: with the Wind Charge
 * glyph file every text in the game drew as empty boxes (seen from 2.10.0 in
 * every 1.17 screenshot; 1.17.1 and later add to the font as they should).
 * So the 1.17 build leaves Minecraft's font alone (versions/1.17/removed.txt)
 * and draws each tag as text set in a font of Breeze's own, which nothing
 * else uses. {@link #forColour} is only ever drawn through {@link #label}:
 * Minecraft's own font does not have these characters here.
 */
public final class TagGlyph {

    public static final String ICON = TagArt.PLAIN.glyph();
    /** Each picture's width in Breeze's font, as on the other versions. */
    public static final int WIDTH = 11;
    /** The pictures carry their own colours, so they are drawn in white. */
    public static final boolean OWN_COLOURS = true;

    private static final ResourceLocation FONT = new ResourceLocation("breeze", "tags");

    private TagGlyph() {}

    public static String forColour(int rgb) {
        return TagArt.of(rgb).glyph();
    }

    /** A picture's character as text in Breeze's font, in {@code tint}. */
    public static Component label(String glyph, int tint) {
        return new TextComponent(glyph).withStyle(s -> s.withColor(TextColor.fromRgb(tint & 0xFFFFFF)).withFont(FONT));
    }
}
