package dev.breeze;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What to draw beside a player's name: their tag, their official role badge and
 * their own custom tag.
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

    private static String text = "[Breeze]";
    private static int color = 0xFF55FFFF;
    private static int nameCooldown = 100;

    private BreezeTag() {}

    public static String text() {
        return text;
    }

    public static int color() {
        return color;
    }

    /** The whole entry for a player, or null when they have nothing special. */
    public static Entry entry(UUID id) {
        return id == null ? null : BY_UUID.get(id);
    }

    public static String text(UUID id) {
        Entry e = entry(id);
        return e != null && e.text != null ? e.text : text;
    }

    public static int color(UUID id) {
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
                String n = info.getProfile().getName();
                UUID pid = info.getProfile().getId();
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

    public static String chatText(String lowerName) {
        Entry e = BY_NAME.get(lowerName);
        if (e != null && e.text != null) return e.text;
        UUID id = NAME_TO_UUID.get(lowerName);
        if (id != null) {
            Entry pe = BY_UUID.get(id);
            if (pe != null && pe.text != null) return pe.text;
            if (BreezeUsers.isBreezeUser(id)) return text;
        }
        return null;
    }

    public static int chatColor(String lowerName) {
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
