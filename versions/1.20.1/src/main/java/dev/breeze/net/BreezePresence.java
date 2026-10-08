package dev.breeze.net;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.breeze.BreezeClient;
import dev.breeze.BreezeTag;
import dev.breeze.Roles;
import dev.breeze.BreezeUsers;
import net.minecraft.client.Minecraft;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class BreezePresence {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static boolean enabled = true;
    private static boolean loaded;
    private static boolean capeUploaded;
    private static long lastAnnounce;
    private static long lastPoll;

    private BreezePresence() {}

    /**
     * Called once at client init. Starts the game token exchange on the HTTP
     * executor, so the first presence and friends polls already have a token
     * instead of each skipping a round while it is fetched.
     */
    public static void register() {
        if (!loaded) loadConfig();
        if (enabled) BreezeApi.warmUp();
    }

    public static void onPlayerDetected(UUID id) {
        BreezeUsers.add(id);
    }

    public static void onPlayerLeft(UUID id) {
        BreezeUsers.remove(id);
    }

    public static void reset() {
        BreezeUsers.clear();
    }

    /**
     * Whether the player allows the mod's network features at all.
     *
     * Where the API lives is no longer this class's business; see
     * {@link BreezeApi#baseUrl()}.
     */
    public static boolean enabled() {
        if (!loaded) loadConfig();
        return enabled;
    }

    private static void loadConfig() {
        loaded = true;
        try {
            Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("config");
            Files.createDirectories(dir);
            Path p = dir.resolve("breeze_presence.json");
            if (!Files.exists(p)) {
                JsonObject root = new JsonObject();
                root.addProperty("enabled", true);
                Files.writeString(p, GSON.toJson(root));
            }
            JsonObject root = GSON.fromJson(Files.readString(p), JsonObject.class);
            if (root != null) {
                if (root.has("enabled")) enabled = root.get("enabled").getAsBoolean();
                // Every install before v1.0.22 was seeded with a plain-HTTP raw
                // IP here, and that address no longer answers. The key is no
                // longer read, but it is removed from the file as well so
                // nobody editing the config believes it still does anything.
                if (root.has("server")) {
                    root.remove("server");
                    Files.writeString(p, GSON.toJson(root));
                    BreezeClient.LOGGER.info("[Breeze] removed the obsolete server address from breeze_presence.json");
                }
            }
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] presence config failed: {}", t.toString());
        }
    }

    public static void tick(Minecraft mc) {
        if (!loaded) loadConfig();
        UUID me = Self.selfUuid();
        if (!enabled || me == null) return;
        dev.breeze.BreezeTag.resolveNames(mc);
        // Every route below requires a token. Without one the polls wait with
        // their timers untouched, so they go out on the first tick that has a
        // token rather than a full interval later.
        if (BreezeApi.gameToken() == null) return;
        long now = System.currentTimeMillis();
        if (now - lastAnnounce > 30000L) {
            lastAnnounce = now;
            try {
                // The API checks this uuid against the token, so it has to be
                // the account's, not whatever id the current server assigned.
                String body = "{\"uuid\":\"" + me + "\",\"name\":\"" + Self.name(mc) + "\"}";
                HttpRequest.Builder b = HttpRequest.newBuilder(BreezeApi.uri("/announce"))
                        .timeout(Duration.ofSeconds(4))
                        .POST(HttpRequest.BodyPublishers.ofString(body));
                if (BreezeApi.authed(b)) {
                    BreezeApi.http().sendAsync(b.build(), HttpResponse.BodyHandlers.discarding())
                            .thenAccept(res -> BreezeApi.onUnauthorized(res.statusCode()));
                }
            } catch (Throwable ignored) {}
            fetchTag();
        }
        if (now - lastPoll > 15000L) {
            lastPoll = now;
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(BreezeApi.uri("/users"))
                        .timeout(Duration.ofSeconds(4)).GET();
                if (!BreezeApi.authed(b)) return;
                BreezeApi.http().sendAsync(b.build(), HttpResponse.BodyHandlers.ofString()).thenAccept(res -> {
                    try {
                        if (res.statusCode() != 200) {
                            BreezeApi.onUnauthorized(res.statusCode());
                            return;
                        }
                        JsonArray arr = GSON.fromJson(res.body(), JsonArray.class);
                        if (arr == null) return;
                        Set<UUID> remote = new HashSet<>();
                        for (JsonElement e : arr) {
                            try {
                                remote.add(UUID.fromString(e.getAsString()));
                            } catch (IllegalArgumentException ignored) {}
                        }
                        BreezeUsers.setRemote(remote);
                    } catch (Throwable ignored) {}
                });
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Poll /tag: the global default, every player's tag, and the role table.
     *
     * The role table arrives whole on every poll and replaces whatever is held
     * locally, so a role added in the backend appears in game on the next poll.
     * Nothing about roles is compiled into this mod.
     */
    private static void fetchTag() {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(BreezeApi.uri("/tag"))
                    .timeout(Duration.ofSeconds(4)).GET();
            if (!BreezeApi.authed(b)) return;
            BreezeApi.http().sendAsync(b.build(), HttpResponse.BodyHandlers.ofString()).thenAccept(res -> {
                try {
                    if (res.statusCode() != 200) {
                        BreezeApi.onUnauthorized(res.statusCode());
                        return;
                    }
                    JsonObject root = GSON.fromJson(res.body(), JsonObject.class);
                    if (root == null) return;

                    if (root.has("text")) BreezeTag.setText(root.get("text").getAsString());
                    if (root.has("color")) BreezeTag.setColor(argb(root.get("color").getAsString(), 0xFF55FFFF));

                    if (root.has("roles") && root.get("roles").isJsonObject()) {
                        Map<String, Roles.Role> roles = new HashMap<>();
                        for (Map.Entry<String, JsonElement> en : root.getAsJsonObject("roles").entrySet()) {
                            try {
                                JsonObject o = en.getValue().getAsJsonObject();
                                roles.put(en.getKey(), new Roles.Role(
                                        en.getKey(),
                                        o.has("name") ? o.get("name").getAsString() : en.getKey(),
                                        o.has("color") ? argb(o.get("color").getAsString(), 0xFF55FFFF) : 0xFF55FFFF,
                                        o.has("priority") ? o.get("priority").getAsInt() : 0,
                                        o.has("icon") && !o.get("icon").isJsonNull() ? o.get("icon").getAsString() : null));
                            } catch (Throwable ignored2) {}
                        }
                        Roles.set(roles);
                    }

                    Map<UUID, BreezeTag.Entry> byUuid = new HashMap<>();
                    if (root.has("players") && root.get("players").isJsonObject()) {
                        for (Map.Entry<String, JsonElement> en : root.getAsJsonObject("players").entrySet()) {
                            try {
                                byUuid.put(UUID.fromString(en.getKey()), entryOf(en.getValue().getAsJsonObject()));
                            } catch (Throwable ignored2) {}
                        }
                    }

                    Map<String, BreezeTag.Entry> byName = new HashMap<>();
                    if (root.has("names") && root.get("names").isJsonObject()) {
                        for (Map.Entry<String, JsonElement> en : root.getAsJsonObject("names").entrySet()) {
                            try {
                                byName.put(en.getKey().toLowerCase(), entryOf(en.getValue().getAsJsonObject()));
                            } catch (Throwable ignored3) {}
                        }
                    }

                    BreezeTag.setPlayers(byUuid);
                    BreezeTag.setNames(byName);
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    /** One player's tag, badge and custom tag, read as a unit. */
    private static BreezeTag.Entry entryOf(JsonObject o) {
        String text = o.has("text") ? o.get("text").getAsString() : null;
        int color = o.has("color") ? argb(o.get("color").getAsString(), BreezeTag.color()) : BreezeTag.color();
        String badge = o.has("badge") && !o.get("badge").isJsonNull() ? o.get("badge").getAsString() : null;
        String custom = o.has("custom") && !o.get("custom").isJsonNull() ? o.get("custom").getAsString() : null;
        int customColor = o.has("customColor") ? argb(o.get("customColor").getAsString(), 0xFFA6ADBA) : 0xFFA6ADBA;
        return new BreezeTag.Entry(text, color, badge, custom, customColor);
    }

    /**
     * Parse a "#RRGGBB" colour into opaque ARGB.
     *
     * Every caller used to inline this, and each one assumed the string parsed.
     * A single malformed colour from the backend therefore aborted the whole
     * enclosing loop and dropped every player after it. Returning a fallback
     * keeps one bad row from costing everyone else their tag.
     */
    private static int argb(String raw, int fallback) {
        try {
            String c = raw.trim().replace("#", "").replace("0x", "");
            long v = Long.parseLong(c, 16);
            if (c.length() <= 6) v |= 0xFF000000L;
            return (int) v;
        } catch (Throwable t) {
            return fallback;
        }
    }
}
