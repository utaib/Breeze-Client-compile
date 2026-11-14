package dev.breeze.cosmetics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The answers of the older cape routes the Wardrobe falls back to, parsed.
 *
 * The live API's cosmetic state answered "no account" for every player until
 * its fix (read in CI on 2026-10-03), so the Wardrobe had nothing to list.
 * These routes read the same tables and work there:
 *   GET /capes/:uuid  the catalogue capes the player owns, by id, followed by
 *                     the API's test images (testcape, onemorecape, ...),
 *                     which are not players' capes and are left out;
 *   GET /capes        the public catalogue, with names and images.
 */
public final class CapeLists {

    private CapeLists() {}

    /** One cape of the public catalogue. */
    public static final class CatalogueCape {
        public final String id;
        public final String name;
        public final String rarity;
        public final String imageUrl;
        public final boolean animated;
        public final int fps;
        public final List<String> frames;

        CatalogueCape(String id, String name, String rarity, String imageUrl, boolean animated, int fps, List<String> frames) {
            this.id = id;
            this.name = name;
            this.rarity = rarity;
            this.imageUrl = imageUrl;
            this.animated = animated;
            this.fps = fps;
            this.frames = frames;
        }
    }

    /** Catalogue cape ids from GET /capes/:uuid, in order, once each. Anything unreadable is empty. */
    public static List<String> ownedIds(String body) {
        Set<String> out = new LinkedHashSet<>();
        try {
            JsonElement root = dev.breeze.Json.parse(body);
            if (!root.isJsonArray()) return List.of();
            for (JsonElement e : root.getAsJsonArray()) {
                if (e == null || !e.isJsonPrimitive()) continue;
                String name = e.getAsString().trim();
                if (CapePolicy.legacySource(name) == CapePolicy.LegacySource.CATALOGUE) out.add(name.toLowerCase());
            }
        } catch (RuntimeException ignored) {
            return List.of();
        }
        return List.copyOf(out);
    }

    /**
     * The public catalogue from GET /capes: {"success":true,"capes":[...]}, or
     * the same under "data". A cape needs an id and an image; anything else
     * is skipped.
     */
    public static List<CatalogueCape> catalogue(String body) {
        List<CatalogueCape> out = new ArrayList<>();
        try {
            JsonElement root = dev.breeze.Json.parse(body);
            if (!root.isJsonObject()) return List.of();
            JsonObject o = root.getAsJsonObject();
            JsonElement capes = o.get("capes");
            if ((capes == null || !capes.isJsonArray()) && o.get("data") != null && o.get("data").isJsonObject()) {
                capes = o.getAsJsonObject("data").get("capes");
            }
            if (capes == null || !capes.isJsonArray()) return List.of();
            for (JsonElement e : capes.getAsJsonArray()) {
                if (e == null || !e.isJsonObject()) continue;
                JsonObject c = e.getAsJsonObject();
                String id = str(c, "id");
                String image = str(c, "image_url");
                if (id == null || image == null) continue;
                List<String> frames = new ArrayList<>();
                JsonElement fr = c.get("animation_frames");
                if (fr != null && fr.isJsonArray()) {
                    for (JsonElement f : fr.getAsJsonArray()) {
                        if (f != null && f.isJsonPrimitive()) frames.add(f.getAsString());
                    }
                }
                boolean animated = bool(c, "is_animated") && frames.size() > 1;
                int fps = c.get("animation_fps") != null && c.get("animation_fps").isJsonPrimitive()
                        ? c.get("animation_fps").getAsInt() : 12;
                String name = str(c, "name");
                out.add(new CatalogueCape(id.toLowerCase(), name == null || name.isBlank() ? "Cape" : name,
                        str(c, "rarity"), image, animated, fps, Collections.unmodifiableList(frames)));
            }
        } catch (RuntimeException ignored) {
            return List.of();
        }
        return out;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    private static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }
}
