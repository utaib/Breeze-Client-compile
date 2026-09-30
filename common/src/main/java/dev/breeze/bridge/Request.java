package dev.breeze.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * One request from the page, parsed and checked before anything acts on it.
 *
 * Wire format: {@code {"v": 1, "id": 7, "action": "modules.setEnabled", "params": {...}}}.
 * Everything here is untrusted: the page is ours, but it runs in a Chromium
 * that MCEF starts with web security disabled, so the router treats every
 * request as hostile until it has passed these checks.
 */
public final class Request {

    public static final int PROTOCOL = 1;
    public static final int MAX_BYTES = 64 * 1024;

    private static final Pattern ACTION = Pattern.compile("[a-z][a-zA-Z]*(\\.[a-z][a-zA-Z]*){1,2}");

    public final long id;
    public final String action;
    public final Params params;

    private Request(long id, String action, Params params) {
        this.id = id;
        this.action = action;
        this.params = params;
    }

    public static Request parse(String raw) {
        if (raw == null || raw.isEmpty()) throw bad("Empty request.");
        if (raw.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw bad("Request too large.");
        JsonElement root;
        try {
            root = dev.breeze.Json.parse(raw);
        } catch (RuntimeException notJson) {
            throw bad("Request is not JSON.");
        }
        if (!root.isJsonObject()) throw bad("Request is not an object.");
        JsonObject o = root.getAsJsonObject();

        if (!o.has("v") || !o.get("v").isJsonPrimitive() || !o.get("v").getAsJsonPrimitive().isNumber()
                || o.get("v").getAsInt() != PROTOCOL) {
            throw bad("Unsupported protocol version.");
        }
        long id;
        try {
            id = o.get("id").getAsLong();
        } catch (RuntimeException e) {
            throw bad("Request has no id.");
        }
        if (id <= 0) throw bad("Request id must be positive.");

        String action;
        try {
            action = o.get("action").getAsString();
        } catch (RuntimeException e) {
            throw bad("Request has no action.");
        }
        if (action.length() > 64 || !ACTION.matcher(action).matches()) throw bad("Malformed action name.");

        JsonElement p = o.get("params");
        JsonObject params;
        if (p == null || p.isJsonNull()) params = new JsonObject();
        else if (p.isJsonObject()) params = p.getAsJsonObject();
        else throw bad("Params must be an object.");

        return new Request(id, action, new Params(params));
    }

    private static BridgeException bad(String message) {
        return new BridgeException(BridgeError.BAD_REQUEST, message);
    }
}
