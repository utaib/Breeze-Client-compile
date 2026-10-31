package dev.breeze.cosmetics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.breeze.Json;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The 3D cosmetics an account owns and which one fills each slot, read from
 * GET /cosmetics/owned/:uuid (docs/COSMETICS.md). Parsing only; the version
 * module fetches it with the game token and equips through
 * POST /cosmetics/equip/:uuid and /cosmetics/unequip/:uuid.
 */
public final class OwnedCosmetics {

    /** The API's slots, in the order the Wardrobe lists them (COSMETIC_SLOTS in the API). */
    public static final List<String> SLOTS = List.of("hat", "wings", "back", "cape", "shield", "pet", "aura", "trail");

    private OwnedCosmetics() {}

    /** One owned cosmetic. */
    public static final class Item {
        public final String id;
        public final String name;
        public final String slot;
        public final boolean equipped;

        public Item(String id, String name, String slot, boolean equipped) {
            this.id = id;
            this.name = name;
            this.slot = slot;
            this.equipped = equipped;
        }
    }

    public static boolean validSlot(String slot) {
        return slot != null && SLOTS.contains(slot);
    }

    /** The slot's label in the Wardrobe. */
    public static String slotLabel(String slot) {
        if (slot == null || slot.isEmpty()) return "";
        return slot.substring(0, 1).toUpperCase(Locale.ROOT) + slot.substring(1);
    }

    /**
     * The answer's owned cosmetics, in slot order then by name. Rows without a
     * cosmetic, an id or a known slot are left out; a malformed answer gives an
     * empty list rather than an exception.
     */
    public static List<Item> parse(String body) {
        List<Item> out = new ArrayList<>();
        JsonElement rootEl;
        try {
            rootEl = Json.parse(body);
        } catch (RuntimeException e) {
            return out;
        }
        if (!rootEl.isJsonObject()) return out;
        JsonObject root = rootEl.getAsJsonObject();
        Map<String, String> equipped = new HashMap<>();
        JsonObject eq = obj(root, "equipped");
        if (eq != null) {
            for (String slot : Json.keys(eq)) {
                JsonElement v = eq.get(slot);
                if (v != null && v.isJsonPrimitive()) equipped.put(slot, v.getAsString());
            }
        }
        JsonElement ownedEl = root.get("owned");
        if (ownedEl == null || !ownedEl.isJsonArray()) return out;
        JsonArray owned = ownedEl.getAsJsonArray();
        for (JsonElement el : owned) {
            if (!el.isJsonObject()) continue;
            JsonObject row = el.getAsJsonObject();
            JsonObject c = obj(row, "cosmetic");
            if (c == null) continue;
            String id = str(c, "id") != null ? str(c, "id") : str(row, "cosmetic_id");
            String slot = str(c, "slot");
            if (id == null || id.isEmpty() || !validSlot(slot)) continue;
            String name = str(c, "name");
            if (name == null || name.isBlank()) name = id;
            out.add(new Item(id, name, slot, id.equals(equipped.get(slot))));
        }
        out.sort(Comparator.<Item>comparingInt(i -> SLOTS.indexOf(i.slot))
                .thenComparing(i -> i.name.toLowerCase(Locale.ROOT)));
        return out;
    }

    private static JsonObject obj(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }
}
