package dev.breeze.ui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.breeze.BreezeClient;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class HudLayout {

    private static final Map<String, int[]> POS = new HashMap<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path path;

    private HudLayout() {}

    public static int[] get(String name) {
        return POS.get(name);
    }

    public static void set(String name, int x, int y) {
        POS.put(name, new int[]{x, y});
        save();
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
            JsonObject root = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            POS.clear();
            for (String key : root.keySet()) {
                JsonObject o = root.getAsJsonObject(key);
                POS.put(key, new int[]{o.get("x").getAsInt(), o.get("y").getAsInt()});
            }
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] hud layout load failed: {}", t.toString());
        }
    }

    public static void save() {
        try {
            JsonObject root = new JsonObject();
            for (Map.Entry<String, int[]> e : POS.entrySet()) {
                JsonObject o = new JsonObject();
                o.addProperty("x", e.getValue()[0]);
                o.addProperty("y", e.getValue()[1]);
                root.add(e.getKey(), o);
            }
            Files.writeString(path(), GSON.toJson(root));
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] hud layout save failed: {}", t.toString());
        }
    }
}
