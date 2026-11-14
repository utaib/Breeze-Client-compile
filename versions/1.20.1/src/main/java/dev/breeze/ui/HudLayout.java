package dev.breeze.ui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.breeze.BreezeClient;
import dev.breeze.hud.HudPlacement;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Where each HUD element sits, saved in config/breeze_hud.json as
 * {"FPS": {"at": "LEFT,TOP,4,4"}}: an anchor and an offset (HudPlacement),
 * so elements keep their place relative to the screen edges when the window
 * or GUI scale changes. Files written before anchors hold {"x": .., "y": ..};
 * those are read as top-left offsets, which is what they were.
 */
public final class HudLayout {

    private static final Map<String, HudPlacement> POS = new HashMap<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path path;

    private HudLayout() {}

    /** The saved placement, or null if the element has never been moved. */
    public static HudPlacement get(String name) {
        return POS.get(name);
    }

    public static void set(String name, HudPlacement placement) {
        if (placement == null) POS.remove(name);
        else POS.put(name, placement);
    }

    private static Path path() {
        if (path == null) {
            Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("config");
            try {
                Files.createDirectories(dir);
            } catch (Throwable ignored) {}
            path = dir.resolve("breeze_hud.json");
        }
        return path;
    }

    public static void load() {
        try {
            Path p = path();
            if (!Files.exists(p)) return;
            JsonObject root = dev.breeze.Json.parse(Files.readString(p)).getAsJsonObject();
            POS.clear();
            for (String key : dev.breeze.Json.keys(root)) {
                JsonElement e = root.get(key);
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                HudPlacement at = null;
                if (o.has("at")) {
                    at = HudPlacement.decode(o.get("at").getAsString());
                } else if (o.has("x") && o.has("y")) {
                    at = HudPlacement.topLeft(o.get("x").getAsInt(), o.get("y").getAsInt());
                }
                if (at != null) POS.put(key, at);
            }
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] hud layout load failed: {}", t.toString());
        }
    }

    public static void save() {
        try {
            JsonObject root = new JsonObject();
            for (Map.Entry<String, HudPlacement> e : POS.entrySet()) {
                JsonObject o = new JsonObject();
                o.addProperty("at", e.getValue().encode());
                root.add(e.getKey(), o);
            }
            Files.writeString(path(), GSON.toJson(root));
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] hud layout save failed: {}", t.toString());
        }
    }
}
