package dev.breeze.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Typed, bounded access to a request's params. Every getter validates and
 * throws INVALID_PARAMS with a message naming the field, so handlers never see
 * a missing, mistyped or oversized value.
 */
public final class Params {

    private final JsonObject raw;

    public Params(JsonObject raw) {
        this.raw = raw;
    }

    public JsonObject raw() {
        return raw;
    }

    public boolean has(String name) {
        return raw.has(name) && !raw.get(name).isJsonNull();
    }

    private JsonPrimitive primitive(String name) {
        JsonElement e = raw.get(name);
        if (e == null || e.isJsonNull()) throw BridgeException.invalid("Missing " + name + ".");
        if (!e.isJsonPrimitive()) throw BridgeException.invalid(name + " has the wrong type.");
        return e.getAsJsonPrimitive();
    }

    public String str(String name, int maxLength) {
        JsonPrimitive p = primitive(name);
        if (!p.isString()) throw BridgeException.invalid(name + " must be text.");
        String s = p.getAsString();
        if (s.length() > maxLength) throw BridgeException.invalid(name + " is too long.");
        return s;
    }

    /** A string, or null when absent or explicitly null. */
    public String optStr(String name, int maxLength) {
        return has(name) ? str(name, maxLength) : null;
    }

    public boolean bool(String name) {
        JsonPrimitive p = primitive(name);
        if (!p.isBoolean()) throw BridgeException.invalid(name + " must be true or false.");
        return p.getAsBoolean();
    }

    public int integer(String name, int min, int max) {
        JsonPrimitive p = primitive(name);
        if (!p.isNumber()) throw BridgeException.invalid(name + " must be a number.");
        double d = p.getAsDouble();
        if (d != Math.rint(d) || Double.isInfinite(d)) throw BridgeException.invalid(name + " must be a whole number.");
        if (d < min || d > max) throw BridgeException.invalid(name + " must be between " + min + " and " + max + ".");
        return (int) d;
    }

    public UUID uuid(String name) {
        String s = str(name, 36);
        try {
            UUID u = UUID.fromString(s);
            // UUID.fromString accepts odd shapes like "1-1-1-1-1"; insist on the canonical one.
            if (!u.toString().equalsIgnoreCase(s)) throw new IllegalArgumentException();
            return u;
        } catch (IllegalArgumentException e) {
            throw BridgeException.invalid(name + " is not a player id.");
        }
    }

    public List<String> strList(String name, int maxItems, int maxLength) {
        JsonElement e = raw.get(name);
        if (e == null || e.isJsonNull()) throw BridgeException.invalid("Missing " + name + ".");
        if (!e.isJsonArray()) throw BridgeException.invalid(name + " must be a list.");
        JsonArray a = e.getAsJsonArray();
        if (a.size() > maxItems) throw BridgeException.invalid(name + " has too many entries.");
        List<String> out = new ArrayList<>(a.size());
        for (JsonElement item : a) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                throw BridgeException.invalid(name + " must contain only text.");
            }
            String s = item.getAsString();
            if (s.length() > maxLength) throw BridgeException.invalid(name + " has an entry that is too long.");
            out.add(s);
        }
        return out;
    }

    /** The raw value, for handlers that validate against a setting's own type. */
    public JsonElement value(String name) {
        JsonElement e = raw.get(name);
        if (e == null) throw BridgeException.invalid("Missing " + name + ".");
        return e;
    }
}
