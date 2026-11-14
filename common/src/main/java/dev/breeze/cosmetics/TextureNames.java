package dev.breeze.cosmetics;

import java.util.Locale;

/**
 * Turns a server-supplied name (a cape's name or id) into one path segment of
 * a Minecraft resource id, which allows only a-z, 0-9, '_', '.' and '-'.
 *
 * Building the id straight from the name threw for any name with a space or a
 * capital outside ASCII, and lower-casing with the machine's locale turned 'I'
 * into a dotless i on Turkish systems, which is not allowed either. The texture
 * was then never registered and the cape never drew.
 *
 * Two names that clean up to the same text ("Wind Charge" and "wind charge")
 * would share one texture, so any name that had to change gets a short hash of
 * the original appended.
 */
public final class TextureNames {

    private static final int MAX = 64;

    private TextureNames() {}

    public static String segment(String raw) {
        if (raw == null || raw.isEmpty()) return "_";
        String lower = raw.toLowerCase(Locale.ROOT);
        StringBuilder b = new StringBuilder(Math.min(lower.length(), MAX));
        for (int i = 0; i < lower.length() && b.length() < MAX; i++) {
            char c = lower.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-';
            b.append(ok ? c : '_');
        }
        String clean = b.toString();
        if (clean.equals(raw)) return clean;
        return clean + "_" + Integer.toHexString(raw.hashCode());
    }
}
