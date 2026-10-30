package dev.breeze;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What to draw beside a player's name: their tag, their official role badge and
 * their own custom tag.
 *
 * Tags are icons, not words (owner, 2026-10-03: "we only need to keep the icon
 * tags"): Breeze's own Wind Charge picture in the tag's colour (owner,
 * 2026-10-08: "the asset we have like the wind charge"). Each colour of the
 * art is a character the mod's font files (assets/minecraft/font/default.json
 * and uniform.json) map to that picture ({@link #icon}), so text drawing puts
 * it above the head, in the tab list and in chat on every version. The API's
 * tag colour picks the picture (Owner red, Developer purple, Creator yellow,
 * Breeze blue; common TagArt), and the picture is drawn in white so its own
 * colours show ({@link #iconTint}).
 *
 * Previously this held six parallel maps (uuid to text, uuid to colour, name to
 * text, name to colour, and so on). 1.0.13 adds a badge slug, custom text and a
 * custom colour, which would have made twelve, and any write that updated some
 * but not all of them produced a player rendered half from one poll and half
 * from another. One {@link Entry} per player removes that failure mode: a player
 * is replaced wholesale or not at all.
 */
public final class BreezeTag {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Everything the renderers need about one player, swapped atomically. */
    public static final class Entry {
        public final String text;
        public final int color;
        /** Official role slug, resolved by the backend. May be null. */
        public final String badge;
        /** The player's own tag, already bracketed. May be null. */
        public final String custom;
        public final int customColor;

        public Entry(String text, int color, String badge, String custom, int customColor) {
            this.text = text;
            this.color = color;
            this.badge = badge;
            this.custom = custom;
            this.customColor = customColor;
        }
    }

    private static final Map<UUID, Entry> BY_UUID = new ConcurrentHashMap<>();
    private static final Map<String, Entry> BY_NAME = new ConcurrentHashMap<>();
    private static final Map<String, UUID> NAME_TO_UUID = new ConcurrentHashMap<>();

    /** The plain Wind Charge: a private-use character the mod's font draws as a picture (see compat/TagGlyph). */
    public static final String ICON = dev.breeze.compat.TagGlyph.ICON;

    private static String text = "[Breeze]";
    private static int color = 0xFF55FFFF;
    private static int nameCooldown = 100;

    private BreezeTag() {}

    /** The tag drawn for a Breeze player: always the icon (the API's tag name is not drawn). */
    public static String text() {
        return icon(color);
    }

    /** The Wind Charge picture for a tag colour, 0xAARRGGBB or 0xRRGGBB. */
    public static String icon(int argb) {
        return dev.breeze.compat.TagGlyph.forColour(argb & 0xFFFFFF);
    }

    /**
     * The colour to draw {@link #icon} in: white, so the picture keeps its own
     * colours (a text colour multiplies a font picture's). On 1.17, whose tag
     * is a plain star, the tag's colour itself.
     */
    public static int iconTint(int argb) {
        return dev.breeze.compat.TagGlyph.OWN_COLOURS ? 0xFFFFFFFF : argb;
    }

    public static int color() {
        return color;
    }

    /** The whole entry for a player, or null when they have nothing special. */
    public static Entry entry(UUID id) {
        return id == null ? null : BY_UUID.get(id);
    }

    public static String text(UUID id) {
        return icon(tagColor(id));
    }

    /**
     * The tag as text to draw: its picture's character in the font that has
     * it (Minecraft's default font, or on 1.17 Breeze's own), in
     * {@link #iconTint}. Prefer this to {@link #text(UUID)}, which only draws
     * where Minecraft's default font holds the pictures.
     */
    public static Component label(int argb) {
        return dev.breeze.compat.TagGlyph.label(icon(argb), iconTint(argb));
    }

    public static Component label(UUID id) {
        return label(tagColor(id));
    }

    /** The colour to draw {@link #text(UUID)} in (see {@link #iconTint}). */
    public static int color(UUID id) {
        return iconTint(tagColor(id));
    }

    /** The colour the API gives the player's tag. */
    public static int tagColor(UUID id) {
        Entry e = entry(id);
        return e != null ? e.color : color;
    }

    /**
     * The player's official role, which is not necessarily the tag they display.
     *
     * A staff member who equips Donator still renders as staff, because the
     * badge comes from users.role on the server and is not something a tag
     * choice can override. Falls back to the plain Breeze role for any Breeze
     * account without a badge, and to null for everyone else.
     */
    public static Roles.Role role(UUID id) {
        Entry e = entry(id);
        if (e != null && e.badge != null) return Roles.get(e.badge);
        return BreezeUsers.isBreezeUser(id) ? Roles.DEFAULT : null;
    }

    /** The player's own custom tag, already bracketed, or null. */
    public static String custom(UUID id) {
        Entry e = entry(id);
        return e != null ? e.custom : null;
    }

    public static int customColor(UUID id) {
        Entry e = entry(id);
        return e != null && e.custom != null ? e.customColor : color;
    }

    public static void setText(String value) {
        if (value != null) text = value;
    }

    public static void setColor(int value) {
        color = value;
    }

    public static void setPlayers(Map<UUID, Entry> players) {
        BY_UUID.clear();
        BY_UUID.putAll(players);
        nameCooldown = 100;
    }

    public static void setNames(Map<String, Entry> names) {
        BY_NAME.clear();
        BY_NAME.putAll(names);
        nameCooldown = 100;
    }

    /**
     * Map online player names onto their uuids.
     *
     * The backend keys by both, because a server may run in offline mode where
     * the uuid it hands out is not the account's real one. The name is then the
     * only stable identifier available.
     */
    public static void resolveNames(Minecraft mc) {
        if (mc.getConnection() == null) return;
        if (++nameCooldown < 100) return;
        nameCooldown = 0;
        try {
            for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
                if (info == null || info.getProfile() == null) continue;
                String n = dev.breeze.compat.Profiles.name(info.getProfile());
                UUID pid = dev.breeze.compat.Profiles.id(info.getProfile());
                if (n == null || pid == null) continue;
                String key = n.toLowerCase();
                NAME_TO_UUID.put(key, pid);
                Entry e = BY_NAME.get(key);
                if (e != null) BY_UUID.put(pid, e);
            }
        } catch (Throwable ignored) {}
    }

    public static boolean hasTag(UUID id) {
        return (id != null && BY_UUID.containsKey(id)) || BreezeUsers.isBreezeUser(id);
    }

    /** The icon before a Breeze player's name in chat, or null for everyone else. */
    public static String chatText(String lowerName) {
        Entry e = BY_NAME.get(lowerName);
        if (e != null) return icon(e.color);
        UUID id = NAME_TO_UUID.get(lowerName);
        if (id != null) {
            if (BY_UUID.containsKey(id) || BreezeUsers.isBreezeUser(id)) return icon(chatTagColor(lowerName));
        }
        return null;
    }

    /** {@link #chatText} as text to draw (see {@link #label(int)}), or null for everyone else. */
    public static Component chatLabel(String lowerName) {
        String icon = chatText(lowerName);
        return icon == null ? null : dev.breeze.compat.TagGlyph.label(icon, chatColor(lowerName));
    }

    /** The colour to draw {@link #chatText} in (see {@link #iconTint}). */
    public static int chatColor(String lowerName) {
        return iconTint(chatTagColor(lowerName));
    }

    private static int chatTagColor(String lowerName) {
        Entry e = BY_NAME.get(lowerName);
        if (e != null) return e.color;
        UUID id = NAME_TO_UUID.get(lowerName);
        if (id != null) {
            Entry pe = BY_UUID.get(id);
            if (pe != null) return pe.color;
        }
        return color;
    }

    private static Path path() {
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("config");
        try {
            Files.createDirectories(dir);
        } catch (Throwable ignored) {}
        return dir.resolve("breeze_tag.json");
    }

    public static void load() {
        try {
            Path p = path();
            if (!Files.exists(p)) {
                JsonObject root = new JsonObject();
                root.addProperty("text", text);
                root.addProperty("color", "#55FFFF");
                Files.writeString(p, GSON.toJson(root));
                return;
            }
            JsonObject root = GSON.fromJson(Files.readString(p), JsonObject.class);
            if (root == null) return;
            if (root.has("text")) setText(root.get("text").getAsString());
            if (root.has("color")) {
                String c = root.get("color").getAsString().trim().replace("#", "").replace("0x", "");
                long v = Long.parseLong(c, 16);
                if (c.length() <= 6) v |= 0xFF000000L;
                setColor((int) v);
            }
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] breeze tag load failed: {}", t.toString());
        }
    }
}
