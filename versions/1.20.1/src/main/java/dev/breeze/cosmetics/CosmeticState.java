package dev.breeze.cosmetics;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.breeze.net.BreezeApi;
import dev.breeze.net.BreezePresence;
import net.minecraft.client.Minecraft;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The mod's single view of a player's cosmetics.
 *
 * Replaces the previous arrangement, which is what actually caused capes and
 * tags to disagree with the launcher. Owned capes, the selected cape and tags
 * each came from a different endpoint returning a different shape, each with
 * its own cache and its own expiry. A cape equipped in the launcher wrote to the
 * database instantly, but the mod only noticed when whichever cache happened to
 * hold that field expired, and the tag cache expired on a different schedule.
 * There was no moment at which the mod was guaranteed to be consistent.
 *
 * Now one endpoint, {@code GET /cosmetics/state/:uuid}, returns everything
 * needed to draw a player, resolved server-side from the same tables the
 * launcher writes to. Polling carries a `revision` hash: when it has not
 * changed, nothing about that player's appearance has changed and all the
 * downstream work (texture fetches, frame decoding) is skipped.
 *
 * Nothing here touches the render thread. Requests are async and results are
 * published into a concurrent map that renderers read without blocking.
 */
public final class CosmeticState {

    /** How often to re-poll a player we already know about. */
    private static final long REFRESH_MS = 15_000;

    /**
     * How often to re-poll the local player.
     *
     * Deliberately much shorter than for other players: this is the one whose
     * cosmetics the user is actively changing in the launcher, and waiting
     * fifteen seconds to see your own new cape is exactly the "I have to toggle
     * the module" complaint this replaces.
     */
    private static final long SELF_REFRESH_MS = 3_000;

    private static final Gson GSON = new Gson();
    private static final Map<UUID, Entry> CACHE = new ConcurrentHashMap<>();

    private CosmeticState() {}

    /** One player's cosmetics, plus the bookkeeping to know when to refresh. */
    public static final class Entry {
        public volatile String role = "user";
        public volatile String username;
        public volatile CapeInfo cape;
        public volatile TagInfo tag;
        /**
         * The official role badge, which is not the same thing as {@link #tag}.
         *
         * A staff member who equips Donator has tag=Donator and badge=Admin.
         * Keeping them apart is what lets the Wardrobe show "you are an Admin"
         * while the player displays whichever tag they prefer.
         */
        public volatile BadgeInfo badge;
        /** The player's own public tag, already bracketed. Null when unset. */
        public volatile String customTag;
        public volatile int customTagColor = 0xFFA6ADBA;
        /** Whether this role is entitled to a custom tag at all. */
        public volatile boolean canCustomTag;
        public final List<CapeInfo> ownedCapes = Collections.synchronizedList(new ArrayList<>());
        public final List<TagInfo> availableTags = Collections.synchronizedList(new ArrayList<>());

        volatile String revision = "";
        volatile long fetchedAt;
        volatile boolean inFlight;
        /** True once a response has been applied, so callers can tell "no cape" from "not loaded". */
        public volatile boolean loaded;
    }

    public static final class CapeInfo {
        public final String id;
        public final String name;
        public final String imageUrl;
        public final String rarity;
        public final boolean animated;
        public final int fps;
        public final List<String> frames;

        CapeInfo(String id, String name, String imageUrl, String rarity,
                 boolean animated, int fps, List<String> frames) {
            this.id = id;
            this.name = name;
            this.imageUrl = imageUrl;
            this.rarity = rarity;
            this.animated = animated;
            this.fps = fps <= 0 ? 12 : fps;
            this.frames = frames;
        }
    }

    /** An official role badge. Fewer fields than a tag: it is not selectable. */
    public static final class BadgeInfo {
        public final String slug;
        public final String name;
        public final int color;
        public final String iconUrl;
        public final int priority;

        BadgeInfo(String slug, String name, int color, String iconUrl, int priority) {
            this.slug = slug;
            this.name = name;
            this.color = color;
            this.iconUrl = iconUrl;
            this.priority = priority;
        }
    }

    public static final class TagInfo {
        public final String id;
        public final String slug;
        public final String name;
        public final int color;
        public final String iconUrl;
        public final int priority;
        public final String source;

        TagInfo(String id, String slug, String name, int color, String iconUrl, int priority, String source) {
            this.id = id;
            this.slug = slug;
            this.name = name;
            this.color = color;
            this.iconUrl = iconUrl;
            this.priority = priority;
            this.source = source;
        }
    }

    /**
     * The cached state for a player, requesting a refresh if it is stale.
     *
     * Never blocks and never returns null: a caller always gets an Entry it can
     * read, with `loaded` false until the first response lands. Renderers run
     * every frame and must not be made to null-check or wait on the network.
     */
    public static Entry get(UUID id) {
        if (id == null) return new Entry();
        Entry e = CACHE.computeIfAbsent(id, k -> new Entry());
        maybeRefresh(id, e);
        return e;
    }

    /** State for the local player. */
    public static Entry self() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? new Entry() : get(mc.player.getUUID());
    }

    /**
     * Force the next poll to happen immediately.
     *
     * Called after the player changes something in the Wardrobe, so the change
     * is visible at once rather than at the next scheduled refresh.
     */
    public static void invalidate(UUID id) {
        Entry e = CACHE.get(id);
        if (e != null) e.fetchedAt = 0;
    }

    public static void invalidateSelf() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) invalidate(mc.player.getUUID());
    }

    private static void maybeRefresh(UUID id, Entry e) {
        if (e.inFlight) return;
        Minecraft mc = Minecraft.getInstance();
        boolean isSelf = mc.player != null && id.equals(mc.player.getUUID());
        long age = System.currentTimeMillis() - e.fetchedAt;
        if (age < (isSelf ? SELF_REFRESH_MS : REFRESH_MS)) return;

        if (!BreezePresence.enabled()) return;

        e.inFlight = true;
        try {
            HttpRequest.Builder b = HttpRequest
                    .newBuilder(BreezeApi.uri("/cosmetics/state/" + id))
                    .timeout(Duration.ofSeconds(6))
                    .GET();
            // Public route that renders every visible player, so it must not
            // wait on sign-in: the token is attached when there is one.
            BreezeApi.authed(b);
            http().sendAsync(b.build(), HttpResponse.BodyHandlers.ofString())
                    .whenComplete((res, err) -> {
                        try {
                            if (err == null && res != null && res.statusCode() == 200) apply(e, res.body());
                        } catch (Throwable ignored) {
                            // A malformed response must not poison the cache;
                            // the previous good state stays until the next poll.
                        } finally {
                            e.fetchedAt = System.currentTimeMillis();
                            e.inFlight = false;
                        }
                    });
        } catch (Throwable t) {
            e.fetchedAt = System.currentTimeMillis();
            e.inFlight = false;
        }
    }

    private static void apply(Entry e, String body) {
        JsonObject root = GSON.fromJson(body, JsonObject.class);
        if (root == null || !root.has("success")) return;

        String rev = str(root, "revision");
        // Unchanged appearance: skip the parse and, more importantly, skip
        // telling the cape system to reload textures it already has.
        if (rev != null && rev.equals(e.revision) && e.loaded) return;

        e.revision = rev == null ? "" : rev;
        e.role = str(root, "role") == null ? "user" : str(root, "role");
        e.username = str(root, "username");

        CapeInfo previous = e.cape;
        e.cape = parseCape(root.get("cape"));
        e.tag = parseTag(root.get("tag"));
        e.badge = parseBadge(root.get("badge"));
        e.customTag = customText(root.get("customTag"));
        e.customTagColor = customColor(root.get("customTag"));
        e.canCustomTag = root.has("canCustomTag") && !root.get("canCustomTag").isJsonNull()
                && root.get("canCustomTag").getAsBoolean();

        e.ownedCapes.clear();
        JsonElement owned = root.get("ownedCapes");
        if (owned != null && owned.isJsonArray()) {
            for (JsonElement el : owned.getAsJsonArray()) {
                CapeInfo c = parseCape(el);
                if (c != null) e.ownedCapes.add(c);
            }
        }

        e.availableTags.clear();
        JsonElement tags = root.get("availableTags");
        if (tags != null && tags.isJsonArray()) {
            for (JsonElement el : tags.getAsJsonArray()) {
                TagInfo t = parseTag(el);
                if (t != null) e.availableTags.add(t);
            }
        }

        e.loaded = true;

        // The equipped cape changed, so any textures built for the old one are
        // now dead weight holding GPU memory.
        String before = previous == null ? null : previous.id;
        String after = e.cape == null ? null : e.cape.id;
        if (before != null && !before.equals(after)) CapeTextures.release(before);
    }

    private static CapeInfo parseCape(JsonElement el) {
        if (el == null || !el.isJsonObject()) return null;
        JsonObject o = el.getAsJsonObject();
        List<String> frames = new ArrayList<>();
        JsonElement fr = o.get("frames");
        if (fr != null && fr.isJsonArray()) {
            for (JsonElement f : fr.getAsJsonArray()) {
                if (!f.isJsonNull()) frames.add(f.getAsString());
            }
        }
        boolean animated = o.has("animated") && !o.get("animated").isJsonNull() && o.get("animated").getAsBoolean();
        return new CapeInfo(
                str(o, "id"), str(o, "name"), str(o, "imageUrl"),
                str(o, "rarity"),
                // Trust the frame count over the flag. A cape marked animated
                // with one frame is a still image, and starting a timer for it
                // would burn work every tick to draw the same pixels.
                animated && frames.size() > 1,
                o.has("fps") && !o.get("fps").isJsonNull() ? o.get("fps").getAsInt() : 12,
                frames);
    }

    private static BadgeInfo parseBadge(JsonElement el) {
        if (el == null || !el.isJsonObject()) return null;
        JsonObject o = el.getAsJsonObject();
        return new BadgeInfo(
                str(o, "slug"), str(o, "name"),
                parseColor(str(o, "color")),
                str(o, "icon"),
                o.has("priority") && !o.get("priority").isJsonNull() ? o.get("priority").getAsInt() : 0);
    }

    /** customTag arrives as {text, color} or is absent entirely. */
    private static String customText(JsonElement el) {
        if (el == null || !el.isJsonObject()) return null;
        return str(el.getAsJsonObject(), "text");
    }

    private static int customColor(JsonElement el) {
        if (el == null || !el.isJsonObject()) return 0xFFA6ADBA;
        String c = str(el.getAsJsonObject(), "color");
        return c == null ? 0xFFA6ADBA : parseColor(c);
    }

    private static TagInfo parseTag(JsonElement el) {
        if (el == null || !el.isJsonObject()) return null;
        JsonObject o = el.getAsJsonObject();
        return new TagInfo(
                str(o, "id"), str(o, "slug"), str(o, "name"),
                parseColor(str(o, "color")),
                str(o, "icon"),
                o.has("priority") && !o.get("priority").isJsonNull() ? o.get("priority").getAsInt() : 0,
                str(o, "source"));
    }

    /** "#RRGGBB" to an opaque ARGB int. Falls back to white on anything odd. */
    private static int parseColor(String hex) {
        if (hex == null) return 0xFFFFFFFF;
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        try {
            return 0xFF000000 | (int) Long.parseLong(h, 16);
        } catch (NumberFormatException e) {
            return 0xFFFFFFFF;
        }
    }

    private static String str(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el == null || el.isJsonNull() ? null : el.getAsString();
    }

    private static HttpClient http() {
        return BreezeApi.http();
    }
}
