package dev.breeze.web;

import dev.breeze.Log;

import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skin and cape URLs for the React 3D player preview.
 *
 * The React wardrobe renders the local player with a three.js skin viewer,
 * which needs texture URLs rather than GL identifiers. Mojang's session server
 * provides both: the profile carries a base64 "textures" property holding the
 * skin URL, the cape URL and the arm model ("slim" or "default").
 *
 * Reconstructed from the shipped September jars ({@code SkinLookup.class}),
 * with the hardening the original lacked:
 *
 * - one shared HttpClient and bounded caches, so the menu being opened and
 *   closed repeatedly cannot queue unbounded profile fetches;
 * - failures are cached briefly, so a profile that 404s once is not re-fetched
 *   every time the menu opens.
 *
 * Nothing here touches the render thread; results are published into a
 * concurrent map the bridge reads without blocking.
 */
public final class SkinLookup {

    /** Skin/cape URLs plus the arm model for one player. */
    public static final class Info {
        public final String skinUrl;
        public final String capeUrl;
        public final boolean slim;

        Info(String skinUrl, String capeUrl, boolean slim) {
            this.skinUrl = skinUrl;
            this.capeUrl = capeUrl;
            this.slim = slim;
        }
    }

    private static final String PROFILE_URL = "https://sessionserver.mojang.com/session/minecraft/profile/";
    private static final long FAILED_RETRY_MS = 60_000L;

    private static final Map<UUID, Info> CACHE = new ConcurrentHashMap<>();
    private static final Map<UUID, Info> EMPTY = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> FAILED_AT = new ConcurrentHashMap<>();
    private static final Set<UUID> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    private static HttpClient http;

    private SkinLookup() {}

    private static HttpClient http() {
        if (http == null) {
            http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
        }
        return http;
    }

    /** The cached info for a player, starting a fetch if there is none. Never blocks. */
    public static Info get(UUID id) {
        if (id == null) return null;
        Info cached = CACHE.get(id);
        if (cached != null) return cached;

        // A profile with no cape (or a failed lookup) is remembered too, so the
        // menu does not re-request it on every open. The empty map only marks
        // "resolved, nothing to show"; the retry clock decides when to try again.
        if (EMPTY.containsKey(id) && !retryDue(id)) return null;
        if (FAILED_AT.containsKey(id) && !retryDue(id)) return null;

        fetchAsync(id);
        return null;
    }

    private static boolean retryDue(UUID id) {
        Long at = FAILED_AT.get(id);
        return at == null || System.currentTimeMillis() - at > FAILED_RETRY_MS;
    }

    private static void fetchAsync(UUID id) {
        if (!IN_FLIGHT.add(id)) return;
        // Cap concurrent profile lookups; the wardrobe is the only caller and
        // it needs exactly one player, so a small in-flight window is plenty.
        if (IN_FLIGHT.size() > 4) {
            IN_FLIGHT.remove(id);
            return;
        }
        try {
            String undashed = id.toString().replace("-", "");
            HttpRequest req = HttpRequest.newBuilder(URI.create(PROFILE_URL + undashed))
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            http().sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
                IN_FLIGHT.remove(id);
                try {
                    if (err != null || res == null || res.statusCode() != 200) {
                        FAILED_AT.put(id, System.currentTimeMillis());
                        return;
                    }
                    JsonObject root = dev.breeze.Json.parse(res.body()).getAsJsonObject();
                    Info info = parse(root);
                    if (info != null) {
                        CACHE.put(id, info);
                        EMPTY.remove(id);
                    } else {
                        // A real profile with no usable textures: remember it
                        // so the wardrobe stops polling, with the retry clock
                        // deciding when to look again.
                        EMPTY.put(id, new Info(null, null, false));
                    }
                    FAILED_AT.remove(id);
                } catch (Throwable t) {
                    FAILED_AT.put(id, System.currentTimeMillis());
                    Log.warn("[Breeze] skin lookup failed for {}: {}", id, t.toString());
                }
            });
        } catch (Throwable t) {
            IN_FLIGHT.remove(id);
            FAILED_AT.put(id, System.currentTimeMillis());
        }
    }

    /** Pull textures.skinUrl / textures.capeUrl / model out of a profile response. */
    private static Info parse(JsonObject root) {
        try {
            if (root == null || !root.has("properties")) return null;
            com.google.gson.JsonArray props = root.getAsJsonArray("properties");
            for (com.google.gson.JsonElement p : props) {
                JsonObject prop = p.getAsJsonObject();
                if (!"textures".equals(prop.get("name").getAsString())) continue;
                String decoded = new String(Base64.getDecoder().decode(prop.get("value").getAsString()),
                        java.nio.charset.StandardCharsets.UTF_8);
                JsonObject tex = dev.breeze.Json.parse(decoded).getAsJsonObject();

                String skin = null;
                String cape = null;
                boolean slim = false;
                if (tex.has("textures")) {
                    JsonObject t = tex.getAsJsonObject("textures");
                    if (t.has("SKIN")) {
                        JsonObject s = t.getAsJsonObject("SKIN");
                        if (s.has("url")) skin = s.get("url").getAsString();
                        if (s.has("metadata") && s.getAsJsonObject("metadata").has("model")) {
                            slim = "slim".equals(s.getAsJsonObject("metadata").get("model").getAsString());
                        }
                    }
                    if (t.has("CAPE") && t.getAsJsonObject("CAPE").has("url")) {
                        cape = t.getAsJsonObject("CAPE").get("url").getAsString();
                    }
                }
                return new Info(skin, cape, slim);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Drop everything; called on disconnect so a session cannot accumulate entries. */
    public static void clear() {
        CACHE.clear();
        EMPTY.clear();
        FAILED_AT.clear();
    }
}
