package dev.breeze.cosmetics;

/**
 * Which cape to draw on a player. Plain Java, so the rule is tested without
 * Minecraft; each version's renderer answers the lookups.
 *
 * The rule:
 *
 * 1. Once the player's cosmetic state has loaded and describes a Breeze
 *    account ({@link #trustState}), it is the truth. An equipped
 *    cape is drawn; while its image is still downloading nothing is drawn, so
 *    another cape never flashes first; with nothing equipped no Breeze cape is
 *    drawn (except your own local cape, below).
 * 2. Before the state has loaded, or if the equipped cape's image failed, the
 *    older per-cape routes are used, so an API without the state route still
 *    works.
 * 3. Your own local cape (an image in the breeze_capes folder, shown only to
 *    you) is used only when the Custom Cape module is on and no Breeze cape is
 *    equipped.
 *
 * This replaces a rule that drew your own equipped cape only while the Custom
 * Cape module was on, and that fell back to the older routes whenever the state
 * said "no cape". Those routes are cached for the whole session, so a cape you
 * or another player had unequipped kept showing.
 *
 * Lookups are called lazily and only when needed, because most of them start a
 * download the first time they are asked.
 */
public final class CapePolicy {

    private CapePolicy() {}

    /** What one renderer knows about one player. T is the version's texture id. */
    public interface Lookup<T> {
        /** The cosmetic state for this player has loaded at least once. */
        boolean stateLoaded();

        /** The loaded state says a cape is equipped. */
        boolean capeEquipped();

        /** The equipped cape's current frame, or null while it downloads. */
        T equippedTexture();

        /** The equipped cape's image could not be downloaded or decoded. */
        boolean equippedFailed();

        /** Your own cape from the older selection route, or null. */
        T legacySelfTexture();

        /** Your own local cape file, or null when there is none. */
        T localTexture();

        /** The player is known to use Breeze, so the older per-player route is worth asking. */
        boolean breezeUser();

        /** Another player's cape from the older per-player route, or null. */
        T legacyPlayerTexture();
    }

    /**
     * Whether a cosmetic state that has loaded can be taken as the truth.
     *
     * The state route answers "no account" (no username, no cape) for a player
     * who has never used Breeze, and also when it could not read the player's
     * row at all. On 2026-10-03 the live API asked the database for a column
     * the users table does not have (equipped_cape_id), so every read failed
     * and every player came back as "no account, no cape". Taking that as the
     * truth hid every Breeze cape in the game. A state without an account is
     * therefore treated as not loaded, and the older routes, which read the
     * equipped cape from user_capes directly, are asked instead.
     *
     * @param loaded   a state response has been applied
     * @param username the account's name in that response, or null
     */
    public static boolean trustState(boolean loaded, String username) {
        return loaded && username != null && !username.isBlank();
    }

    /** Where a cape named by the older selection route (/selected/:uuid) is drawn from. */
    public enum LegacySource {
        /** Nothing equipped, or a name that is not a cape in the account database. */
        NONE,
        /** A catalogue cape: the answer is its id, its image is /capefile/<id>. */
        CATALOGUE,
        /** The player's own upload (users.cape_url): its image is /cape/:uuid. */
        PERSONAL
    }

    private static final java.util.regex.Pattern CAPE_ID = java.util.regex.Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * What an answer from /selected/:uuid names.
     *
     * The API reads it from the account database (Supabase): a catalogue
     * cape's id, or "personal" for an uploaded one. When the database has
     * nothing it falls back to a folder of test images kept on the API server
     * (mod_capes), which are not players' capes. Those names are never drawn.
     */
    public static LegacySource legacySource(String selected) {
        if (selected == null) return LegacySource.NONE;
        String s = selected.trim();
        if (CAPE_ID.matcher(s).matches()) return LegacySource.CATALOGUE;
        if (s.equalsIgnoreCase("personal")) return LegacySource.PERSONAL;
        return LegacySource.NONE;
    }

    /**
     * The texture to draw, or null for no Breeze cape (the game then draws the
     * player's own Minecraft cape, if they have one).
     *
     * @param self           the player is the one playing
     * @param localCapeOn    the Custom Cape module is on
     */
    public static <T> T pick(boolean self, boolean localCapeOn, Lookup<T> l) {
        if (l.stateLoaded()) {
            if (!l.capeEquipped()) return self && localCapeOn ? l.localTexture() : null;
            T equipped = l.equippedTexture();
            if (equipped != null) return equipped;
            if (!l.equippedFailed()) return null;
            // The equipped cape's image failed: try the older routes below.
        }
        if (self) {
            T legacy = l.legacySelfTexture();
            if (legacy != null) return legacy;
            return localCapeOn ? l.localTexture() : null;
        }
        return l.breezeUser() ? l.legacyPlayerTexture() : null;
    }
}
