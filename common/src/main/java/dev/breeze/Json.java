package dev.breeze;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The two Gson calls Breeze makes that newer Gson has shortcuts for. Gson
 * ships inside Minecraft, and Minecraft 1.17 ships 2.8.0, which has neither
 * JsonParser.parseString (2.8.6) nor JsonObject.keySet (2.8.1). These use only
 * what every shipped Gson has.
 */
public final class Json {

    private static final Gson GSON = new Gson();

    private Json() {}

    /**
     * Parses JSON text, leniently as JsonParser.parseString does; JsonNull for
     * empty text. Throws Gson's JsonParseException on malformed text.
     */
    public static JsonElement parse(String text) {
        JsonElement e = GSON.fromJson(text, JsonElement.class);
        return e == null ? JsonNull.INSTANCE : e;
    }

    /** An object's member names, in the object's order. */
    public static Set<String> keys(JsonObject o) {
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) out.add(e.getKey());
        return out;
    }
}
