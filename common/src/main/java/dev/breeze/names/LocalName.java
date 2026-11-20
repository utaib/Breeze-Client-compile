package dev.breeze.names;

/**
 * The rules for the local display name: the name a player sees for themself
 * on their own computer while the server, and everyone else, keep seeing the
 * account's real name.
 *
 * Two jobs, both free of Minecraft types so they can be tested here:
 * {@link #clean} decides what may be shown (no formatting codes, no control
 * or invisible characters, nothing that could pass for a Breeze tag), and
 * {@link #swap} puts the alias in place of the real name inside a piece of
 * text that the caller has already identified as the player's own (by uuid);
 * it never decides on its own whose name a piece of text is.
 */
public final class LocalName {

    /** Longest alias, in characters. Minecraft names are 16; a little room for "UthabYT [Live]". */
    public static final int MAX = 24;

    private LocalName() {}

    /**
     * The alias to keep, trimmed with inner runs of spaces made one, or an
     * IllegalArgumentException whose message says what to change.
     */
    public static String clean(String raw) {
        if (raw == null) throw new IllegalArgumentException("Type a name.");
        StringBuilder out = new StringBuilder();
        boolean space = false;
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                space = out.length() > 0;
                continue;
            }
            String problem = refused(cp);
            if (problem != null) throw new IllegalArgumentException(problem);
            if (space) out.append(' ');
            space = false;
            out.appendCodePoint(cp);
        }
        if (out.length() == 0) throw new IllegalArgumentException("Type a name.");
        if (out.codePointCount(0, out.length()) > MAX) {
            throw new IllegalArgumentException("Use at most " + MAX + " characters.");
        }
        return out.toString();
    }

    private static String refused(int cp) {
        if (cp == '§') return "Formatting codes (§) are not allowed.";
        if (!Character.isBmpCodePoint(cp)) return "Minecraft cannot draw one of those characters (emoji and other symbols outside its font).";
        int type = Character.getType(cp);
        switch (type) {
            case Character.CONTROL:
            case Character.FORMAT:
            case Character.SURROGATE:
            case Character.UNASSIGNED:
            case Character.LINE_SEPARATOR:
            case Character.PARAGRAPH_SEPARATOR:
                return "Use letters, numbers, spaces and ordinary symbols only.";
            case Character.PRIVATE_USE:
                // Breeze draws its tags (the Wind Charge) as private-use font
                // glyphs; a name must not be able to show one.
                return "That character is reserved for Breeze's own icons.";
            default:
                return null;
        }
    }

    /** A character Minecraft allows in an account name. */
    public static boolean nameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }

    /**
     * {@code text} with each whole-word {@code real} replaced by {@code alias}.
     * Whole word in Minecraft's name alphabet: "Uthab" in "[Owner] Uthab:" is
     * replaced, in "Uthab_2" or "xUthab" it is not. Case must match, as the
     * server spells the name. Returns {@code text} itself when nothing matched,
     * so the caller can tell nothing changed.
     */
    public static String swap(String text, String real, String alias) {
        if (text == null || real == null || real.isEmpty() || alias == null || text.length() < real.length()) return text;
        StringBuilder out = null;
        int from = 0;
        int at = text.indexOf(real);
        while (at >= 0) {
            int end = at + real.length();
            boolean before = at == 0 || !nameChar(text.charAt(at - 1));
            boolean after = end == text.length() || !nameChar(text.charAt(end));
            if (before && after) {
                if (out == null) out = new StringBuilder(text.length() + alias.length());
                out.append(text, from, at).append(alias);
                from = end;
                at = text.indexOf(real, end);
            } else {
                at = text.indexOf(real, at + 1);
            }
        }
        if (out == null) return text;
        out.append(text, from, text.length());
        return out.toString();
    }
}
