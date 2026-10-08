package dev.breeze.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.breeze.Log;
import dev.breeze.bridge.BridgeException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * The Breeze interface settings: theme, accent, size, motion, transparency,
 * title screen replacement and world dimming.
 *
 * These are local to this computer and stored in config/breeze-ui.json. They
 * are never sent to the Breeze API and are never presented as account data.
 *
 * Loading is forgiving (a damaged or hand-edited file keeps every value it can
 * and defaults the rest), writing through {@link #set} is strict (a bad value
 * from the page is refused, never clamped silently), and saving is atomic so a
 * crash mid-write cannot leave a truncated file.
 */
public final class UiSettings {

    public static final List<String> THEMES = List.of(
            "glass", "black", "white", "silver", "midnight", "forest", "ember", "arctic", "rose", "abyss");
    public static final List<String> ACCENTS = List.of("blue", "green", "yellow", "pink", "red");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public String theme = "glass";
    public String accent = "blue";
    /** 0 is automatic, otherwise 75 to 150 percent of automatic, in steps of 5. */
    public int uiScale = 0;
    public boolean animations = true;
    public boolean reduceTransparency = false;
    public boolean replaceTitleScreen = true;
    public int backdropDim = 55;

    public UiSettings copy() {
        return fromJson(toJson());
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("theme", theme);
        o.addProperty("accent", accent);
        o.addProperty("uiScale", uiScale);
        o.addProperty("animations", animations);
        o.addProperty("reduceTransparency", reduceTransparency);
        o.addProperty("replaceTitleScreen", replaceTitleScreen);
        o.addProperty("backdropDim", backdropDim);
        return o;
    }

    /** Every valid value is kept; every invalid or missing one is the default. */
    public static UiSettings fromJson(JsonObject o) {
        UiSettings s = new UiSettings();
        if (o == null) return s;
        for (String key : dev.breeze.Json.keys(o)) {
            try {
                s.set(key, o.get(key));
            } catch (BridgeException | IllegalStateException | UnsupportedOperationException ignored) {
                // keep the default for this one key
            }
        }
        return s;
    }

    /**
     * Change one setting from a page request.
     *
     * @throws BridgeException INVALID_PARAMS naming the problem
     */
    public void set(String key, JsonElement value) {
        switch (key) {
            case "theme" -> theme = oneOf(key, value, THEMES);
            case "accent" -> accent = oneOf(key, value, ACCENTS);
            case "uiScale" -> {
                int v = whole(key, value);
                if (v != 0 && (v < 75 || v > 150 || v % 5 != 0)) {
                    throw BridgeException.invalid("Interface size must be automatic or 75% to 150% in steps of 5.");
                }
                uiScale = v;
            }
            case "animations" -> animations = flag(key, value);
            case "reduceTransparency" -> reduceTransparency = flag(key, value);
            case "replaceTitleScreen" -> replaceTitleScreen = flag(key, value);
            case "backdropDim" -> {
                int v = whole(key, value);
                if (v < 0 || v > 90) throw BridgeException.invalid("World dimming must be between 0% and 90%.");
                backdropDim = v;
            }
            default -> throw BridgeException.invalid("There is no setting called " + key + ".");
        }
    }

    private static String oneOf(String key, JsonElement v, List<String> allowed) {
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString() || !allowed.contains(v.getAsString())) {
            throw BridgeException.invalid(key + " must be one of " + String.join(", ", allowed) + ".");
        }
        return v.getAsString();
    }

    private static boolean flag(String key, JsonElement v) {
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isBoolean()) {
            throw BridgeException.invalid(key + " must be on or off.");
        }
        return v.getAsBoolean();
    }

    private static int whole(String key, JsonElement v) {
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) {
            throw BridgeException.invalid(key + " must be a number.");
        }
        JsonPrimitive p = v.getAsJsonPrimitive();
        double d = p.getAsDouble();
        if (d != Math.rint(d) || Math.abs(d) > 1_000_000) throw BridgeException.invalid(key + " must be a whole number.");
        return (int) d;
    }

    // ── persistence ─────────────────────────────────────────────────────────

    public static UiSettings load(Path file) {
        try {
            if (!Files.isRegularFile(file)) return new UiSettings();
            JsonElement root = dev.breeze.Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            return fromJson(root.isJsonObject() ? root.getAsJsonObject() : null);
        } catch (Exception unreadable) {
            Log.warn("[Breeze] interface settings unreadable, using defaults: {}", unreadable.toString());
            return new UiSettings();
        }
    }

    public void save(Path file) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(toJson()), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** RGB for the accent, used to recolour the in-game HUD accent. */
    public int accentRgb() {
        return switch (accent) {
            case "green" -> 0x3DD68C;
            case "yellow" -> 0xF5C542;
            case "pink" -> 0xF28BC0;
            case "red" -> 0xEF6461;
            default -> 0x78B2FF;
        };
    }
}
