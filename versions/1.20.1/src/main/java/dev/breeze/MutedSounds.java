package dev.breeze;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class MutedSounds {

    private static final Set<String> MUTED = ConcurrentHashMap.newKeySet();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private MutedSounds() {}

    public static void add(String id) {
        if (id != null) MUTED.add(id);
    }

    public static void remove(String id) {
        if (id != null) MUTED.remove(id);
    }

    public static boolean isMuted(String id) {
        return id != null && MUTED.contains(id);
    }

    public static boolean isEmpty() {
        return MUTED.isEmpty();
    }

    public static void clear() {
        MUTED.clear();
    }

    private static Path path() {
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("config");
        try {
            Files.createDirectories(dir);
        } catch (Throwable ignored) {}
        return dir.resolve("breeze_muted_sounds.json");
    }

    public static void load() {
        try {
            Path p = path();
            if (!Files.exists(p)) {
                JsonObject root = new JsonObject();
                root.add("muted", new JsonArray());
                Files.writeString(p, GSON.toJson(root));
                return;
            }
            JsonObject root = GSON.fromJson(Files.readString(p), JsonObject.class);
            if (root == null || !root.has("muted")) return;
            MUTED.clear();
            for (JsonElement e : root.getAsJsonArray("muted")) {
                String s = e.getAsString().trim();
                if (!s.isEmpty()) MUTED.add(s);
            }
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] muted sounds load failed: {}", t.toString());
        }
    }
}
