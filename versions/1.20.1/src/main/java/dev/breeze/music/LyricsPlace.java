package dev.breeze.music;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.breeze.BreezeClient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where the player put the lyrics in each world (the Lyrics module's "In the
 * world"): one spot per world and dimension, kept in
 * config/breeze-lyrics.json. Only this player sees them; nothing is sent
 * anywhere.
 */
public final class LyricsPlace {

    private LyricsPlace() {}

    private static final int MAX_PLACES = 64;
    private static final Gson GSON = new Gson();
    private static Map<String, double[]> places;

    private static Path path() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("breeze-lyrics.json");
    }

    private static synchronized Map<String, double[]> load() {
        if (places != null) return places;
        Map<String, double[]> out = new LinkedHashMap<>();
        try {
            Path p = path();
            if (Files.exists(p) && Files.size(p) < 200_000) {
                JsonObject root = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), JsonObject.class);
                if (root != null) {
                    for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                        if (!e.getValue().isJsonArray() || e.getValue().getAsJsonArray().size() != 3 || out.size() >= MAX_PLACES) continue;
                        double[] v = new double[3];
                        for (int i = 0; i < 3; i++) v[i] = e.getValue().getAsJsonArray().get(i).getAsDouble();
                        if (Double.isFinite(v[0]) && Double.isFinite(v[1]) && Double.isFinite(v[2])) out.put(e.getKey(), v);
                    }
                }
            }
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] lyrics places not read: {}", t.toString());
        }
        places = out;
        return out;
    }

    private static synchronized void save() {
        try {
            JsonObject root = new JsonObject();
            for (Map.Entry<String, double[]> e : load().entrySet()) {
                com.google.gson.JsonArray a = new com.google.gson.JsonArray();
                for (double d : e.getValue()) a.add(Math.round(d * 100) / 100.0);
                root.add(e.getKey(), a);
            }
            Path p = path();
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] lyrics places not saved: {}", t.toString());
        }
    }

    /** This world and dimension, as a key: a singleplayer world by its name, a server by its address. */
    static String here(Minecraft mc) {
        if (mc.level == null) return null;
        String world = "?";
        try {
            if (mc.getSingleplayerServer() != null) world = "world:" + mc.getSingleplayerServer().getWorldData().getLevelName();
            else if (mc.getCurrentServer() != null) world = "server:" + mc.getCurrentServer().ip;
        } catch (Throwable ignored) {
        }
        String key = world + "|" + mc.level.dimension();
        return key.length() > 300 ? key.substring(0, 300) : key;
    }

    /** The spot in this world and dimension, or null when none was chosen here. */
    public static synchronized Vec3 get(Minecraft mc) {
        String key = here(mc);
        double[] v = key == null ? null : load().get(key);
        return v == null ? null : new Vec3(v[0], v[1], v[2]);
    }

    public static synchronized boolean placedHere(Minecraft mc) {
        String key = here(mc);
        return key != null && load().containsKey(key);
    }

    /**
     * Puts the lyrics where the player looks: at the block or entity under
     * the crosshair, else six blocks ahead. False outside a world.
     */
    public static boolean placeAtLook(Minecraft mc) {
        if (mc.player == null || mc.level == null) return false;
        Vec3 eye = mc.player.getEyePosition(1f);
        Vec3 spot;
        HitResult hit = mc.hitResult;
        if (hit != null && (hit.getType() == HitResult.Type.BLOCK || hit.getType() == HitResult.Type.ENTITY)) {
            // A little toward the player and up, so the words stand in front of the block, not in it.
            Vec3 back = eye.subtract(hit.getLocation());
            double len = Math.max(0.01, back.length());
            spot = hit.getLocation().add(back.x / len * 0.4, 0.6, back.z / len * 0.4);
        } else {
            Vec3 look = mc.player.getViewVector(1f);
            spot = eye.add(look.x * 6, look.y * 6, look.z * 6);
        }
        return place(mc, spot);
    }

    public static synchronized boolean place(Minecraft mc, Vec3 spot) {
        String key = here(mc);
        if (key == null || spot == null) return false;
        Map<String, double[]> all = load();
        all.remove(key);
        // The oldest go first once there are too many worlds.
        while (all.size() >= MAX_PLACES) all.remove(all.keySet().iterator().next());
        all.put(key, new double[]{spot.x, spot.y, spot.z});
        save();
        return true;
    }

    public static synchronized void removeHere(Minecraft mc) {
        String key = here(mc);
        if (key != null && load().remove(key) != null) save();
    }
}
