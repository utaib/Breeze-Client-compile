package dev.breeze.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Set;

/**
 * Java-to-page events, as the JavaScript that delivers them.
 *
 * The payload is embedded in a script, so it must not be able to break out of
 * it. Gson's default escaping turns {@code < > & = '} into unicode escapes and
 * always escapes U+2028 and U+2029, which are line terminators in older
 * JavaScript. What comes out is one JSON literal and nothing else.
 */
public final class Events {

    /** The event names in contract/bridge.json. Anything else is refused. */
    public static final Set<String> NAMES = Set.of(
            "key.escape", "settings.changed", "modules.changed", "game.changed", "window.resized");

    private static final Gson GSON = new GsonBuilder().create();

    private Events() {}

    public static String script(String type, JsonElement payload) {
        if (!NAMES.contains(type)) throw new IllegalArgumentException("Unknown event " + type);
        JsonObject event = new JsonObject();
        event.addProperty("type", type);
        if (payload != null) event.add("payload", payload);
        return "window.__breezeEvent&&window.__breezeEvent(" + GSON.toJson(event) + ")";
    }
}
