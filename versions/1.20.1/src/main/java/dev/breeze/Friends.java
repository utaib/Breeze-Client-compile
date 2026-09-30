package dev.breeze;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class Friends {

    private static final Set<UUID> FRIENDS = ConcurrentHashMap.newKeySet();
    private static final Set<String> NAMES = ConcurrentHashMap.newKeySet();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static int cooldown = 90;

    private Friends() {}

    public static void add(UUID id) {
        if (id != null) FRIENDS.add(id);
    }

    public static void remove(UUID id) {
        if (id != null) FRIENDS.remove(id);
    }

    public static boolean isFriend(UUID id) {
        return id != null && FRIENDS.contains(id);
    }

    public static void clear() {
        FRIENDS.clear();
    }

    private static Path path() {
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("config");
        try {
            Files.createDirectories(dir);
        } catch (Throwable ignored) {}
        return dir.resolve("breeze_friends.json");
    }

    public static void load() {
        try {
            Path p = path();
            if (!Files.exists(p)) {
                JsonObject root = new JsonObject();
                root.add("friends", new JsonArray());
                Files.writeString(p, GSON.toJson(root));
                return;
            }
            JsonObject root = GSON.fromJson(Files.readString(p), JsonObject.class);
            NAMES.clear();
            if (root == null || !root.has("friends")) return;
            for (JsonElement e : root.getAsJsonArray("friends")) {
                String s = e.getAsString().trim();
                if (s.isEmpty()) continue;
                try {
                    FRIENDS.add(UUID.fromString(s));
                } catch (IllegalArgumentException ex) {
                    NAMES.add(s.toLowerCase());
                }
            }
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] friends load failed: {}", t.toString());
        }
    }

    public static void tick(Minecraft mc) {
        if (NAMES.isEmpty() || mc.getConnection() == null) return;
        if (++cooldown < 100) return;
        cooldown = 0;
        try {
            for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
                if (info == null || info.getProfile() == null) continue;
                String n = dev.breeze.compat.Profiles.name(info.getProfile());
                if (n != null && NAMES.contains(n.toLowerCase())) add(dev.breeze.compat.Profiles.id(info.getProfile()));
            }
        } catch (Throwable ignored) {}
    }
}
