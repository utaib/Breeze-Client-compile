package dev.breeze.ui;

/**
 * Which of Breeze's Wind Charge pictures a tag shows, chosen from the tag's
 * colour in the API: red (Owner), purple (Developer, Admin), yellow (Creator,
 * Donator), blue (Breeze), green, pink, and the plain one for a grey.
 *
 * The pictures are the repository's own art (sty/*wind_charge.png, the same
 * files as frontend/src/assets/wind-charge*.png), cut to the 12x10 pixels the
 * charge covers and put side by side in this order in the mod's font sheet
 * (assets/breeze/textures/font/tags.png). Each is a character of the font from
 * U+EB2E and is drawn in its own colours, so the text around it is white.
 */
public enum TagArt {
    PLAIN, BLUE, GREEN, PINK, PURPLE, RED, YELLOW;

    /** The first of the font's tag characters; one per value, in order. */
    public static final int FIRST = 0xEB2E;

    public String glyph() {
        return String.valueOf((char) (FIRST + ordinal()));
    }

    /** The picture for a tag colour, 0xRRGGBB (alpha ignored). */
    public static TagArt of(int rgb) {
        float r = ((rgb >> 16) & 0xFF) / 255f, g = ((rgb >> 8) & 0xFF) / 255f, b = (rgb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        float sat = max == 0 ? 0 : (max - min) / max;
        if (sat < 0.2f || max < 0.15f) return PLAIN;
        float d = max - min, h;
        if (max == r) h = 60 * (((g - b) / d) % 6);
        else if (max == g) h = 60 * ((b - r) / d + 2);
        else h = 60 * ((r - g) / d + 4);
        if (h < 0) h += 360;
        if (h < 20 || h >= 345) return RED;
        if (h < 75) return YELLOW;
        if (h < 165) return GREEN;
        if (h < 250) return BLUE;
        if (h < 310) return PURPLE;
        return PINK;
    }
}
