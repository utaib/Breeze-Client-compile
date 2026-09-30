package dev.breeze.cosmetics;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.breeze.cosmetics.model.CosmeticRig;
import dev.breeze.net.BreezeApi;
import dev.breeze.net.BreezePresence;
import dev.breeze.net.Self;
import net.minecraft.client.Minecraft;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The 3D cosmetics each player is wearing, from GET /cosmetics/equipped/:uuid
 * (docs/COSMETICS.md): which model, where it attaches, the creator's
 * placement or the player's own, and which clip plays for each animation role.
 * Polled like the cape state: a player in view is looked up, and looked up
 * again after a while, so equipping in the Wardrobe or launcher shows up
 * without a relog.
 */
public final class WornCosmetics {

    private static final long REFRESH_MS = 30_000;
    private static final long SELF_REFRESH_MS = 5_000;
    private static final Gson GSON = new Gson();
    private static final Map<UUID, Entry> CACHE = new ConcurrentHashMap<>();
    /** Set by the self-test only: what a player wears, instead of asking the API. */
    private static final Map<UUID, List<Worn>> TEST = new ConcurrentHashMap<>();

    private WornCosmetics() {}

    /** One equipped cosmetic. */
    public static final class Worn {
        public final String id;
        public final String slot;
        public final String name;
        public final String modelUrl;
        public final CosmeticRig.Attachment attachment;
        public final CosmeticRig.Transform transform;
        public final Map<String, String> roles;
        /** The model's rest bounds from the API's metadata, or null to measure. */
        public final float[] bounds;

        public Worn(String id, String slot, String name, String modelUrl, CosmeticRig.Attachment attachment,
                    CosmeticRig.Transform transform, Map<String, String> roles, float[] bounds) {
            this.id = id;
            this.slot = slot;
            this.name = name;
            this.modelUrl = modelUrl;
            this.attachment = attachment;
            this.transform = transform;
            this.roles = roles;
            this.bounds = bounds;
        }
    }

    private static final class Entry {
        volatile List<Worn> worn = List.of();
        volatile long fetchedAt;
        volatile boolean inFlight;
    }

    /** What a player is wearing, as last fetched (empty until then). */
    public static List<Worn> get(UUID id) {
        if (id == null) return List.of();
        List<Worn> test = TEST.get(id);
        if (test != null) return test;
        Minecraft mc = Minecraft.getInstance();
        boolean self = mc.player != null && id.equals(mc.player.getUUID());
        // Your own cosmetics follow your account, which is what the Wardrobe
        // equips; on an offline-mode server the in-world id is a different one.
        UUID lookup = self && Self.selfUuid() != null ? Self.selfUuid() : id;
        Entry e = CACHE.computeIfAbsent(lookup, k -> new Entry());
        maybeRefresh(lookup, e, self);
        return e.worn;
    }

    /** For the self-test: make a player wear these without the API. */
    public static void setForTest(UUID id, List<Worn> worn) {
        TEST.put(id, worn);
    }

    private static void maybeRefresh(UUID id, Entry e, boolean self) {
        if (e.inFlight) return;
        if (System.currentTimeMillis() - e.fetchedAt < (self ? SELF_REFRESH_MS : REFRESH_MS)) return;
        if (!BreezePresence.enabled()) return;
        e.inFlight = true;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(BreezeApi.uri("/cosmetics/equipped/" + id))
                    .timeout(Duration.ofSeconds(6)).GET();
            BreezeApi.http().sendAsync(b.build(), HttpResponse.BodyHandlers.ofString())
                    .whenComplete((res, err) -> {
                        try {
                            if (err == null && res != null && res.statusCode() == 200) e.worn = parse(res.body());
                        } catch (Throwable ignored) {
                            // A malformed answer keeps what was there.
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

    static List<Worn> parse(String body) {
        JsonObject root = GSON.fromJson(body, JsonObject.class);
        List<Worn> out = new ArrayList<>();
        if (root == null || !root.has("equipped") || !root.get("equipped").isJsonArray()) return out;
        for (JsonElement el : root.getAsJsonArray("equipped")) {
            if (!el.isJsonObject()) continue;
            JsonObject row = el.getAsJsonObject();
            JsonObject c = obj(row, "cosmetic");
            if (c == null) continue;
            String url = str(c, "model_url");
            String id = str(c, "id");
            if (url == null || id == null) continue;
            String slot = str(c, "slot") != null ? str(c, "slot") : str(row, "slot");
            JsonObject meta = obj(c, "metadata");
            String attach = meta == null ? null : str(meta, "attachment");
            // The player's own placement wins over the creator's.
            JsonObject placement = obj(row, "placement");
            JsonObject tr = placement != null && obj(placement, "transform") != null ? obj(placement, "transform")
                    : placement != null && placement.has("offset") ? placement
                    : meta == null ? null : obj(meta, "transform");
            Map<String, String> roles = new HashMap<>();
            JsonObject anim = meta == null ? null : obj(meta, "animations");
            JsonObject r = anim == null ? null : obj(anim, "roles");
            if (r != null) {
                for (String k : dev.breeze.Json.keys(r)) {
                    JsonElement v = r.get(k);
                    if (v != null && v.isJsonPrimitive() && !v.getAsString().isEmpty()) roles.put(k, v.getAsString());
                }
            }
            if (!roles.containsKey("idle") && str(c, "idle_animation") != null) roles.put("idle", str(c, "idle_animation"));
            float[] bounds = null;
            JsonObject b = meta == null ? null : obj(meta, "bounds");
            if (b != null && b.has("min") && b.has("max") && b.get("min").isJsonArray() && b.get("max").isJsonArray()) {
                JsonArray mn = b.getAsJsonArray("min"), mx = b.getAsJsonArray("max");
                if (mn.size() == 3 && mx.size() == 3) {
                    bounds = new float[]{mn.get(0).getAsFloat(), mn.get(1).getAsFloat(), mn.get(2).getAsFloat(),
                            mx.get(0).getAsFloat(), mx.get(1).getAsFloat(), mx.get(2).getAsFloat()};
                }
            }
            out.add(new Worn(id, slot, str(c, "name"), url, CosmeticRig.Attachment.of(attach, slot),
                    transform(tr), roles, bounds));
        }
        return out;
    }

    private static CosmeticRig.Transform transform(JsonObject t) {
        if (t == null) return CosmeticRig.Transform.NONE;
        return new CosmeticRig.Transform(vec(t, "offset"), vec(t, "rotation"),
                t.has("scale") && t.get("scale").isJsonPrimitive() ? t.get("scale").getAsFloat() : 1f);
    }

    private static float[] vec(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray()) return null;
        JsonArray a = o.getAsJsonArray(key);
        float[] v = new float[3];
        for (int i = 0; i < 3 && i < a.size(); i++) v[i] = a.get(i).getAsFloat();
        return v;
    }

    private static JsonObject obj(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : null;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }
}
