package dev.breeze.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.breeze.settings.Renames;
import dev.breeze.settings.Setting;
import com.google.gson.JsonParser;
import dev.breeze.BreezeClient;
import dev.breeze.Module;
import dev.breeze.ModuleManager;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class BreezeConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path path;

    private BreezeConfig() {}

    public static Path path() {
        if (path == null) {
            Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("config");
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                BreezeClient.LOGGER.warn("[Breeze] Could not create config dir: {}", e.toString());
            }
            path = dir.resolve("breeze.json");
        }
        return path;
    }

    public static void load() {
        try {
            Path p = path();
            if (!Files.exists(p)) return;
            JsonObject root = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            // Not an early return on a missing "modules" key. Settings live in a
            // sibling key, and returning here would silently drop all of them
            // for any config that has settings but no enabled modules.
            if (root.has("modules") && root.get("modules").isJsonObject()) {
                JsonObject mods = root.getAsJsonObject("modules");
                for (Module m : ModuleManager.getModules()) {
                    // A renamed module finds a save made under its old name.
                    String key = Renames.savedKey(m.getName(), m.earlierNames(), mods::has);
                    if (key != null) {
                        boolean enabled = mods.get(key).getAsBoolean();
                        m.setStateSilently(false);
                        if (enabled) m.setEnabled(true);
                        else m.setStateSilently(false);
                        if (!key.equals(m.getName())) m.migratedFrom(key);
                    }
                }
            }
            loadSettings(root);
            BreezeClient.LOGGER.info("[Breeze] Loaded config from {}", p);
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] Could not load config: {}", t.toString());
        }
    }

    public static void save() {
        try {
            JsonObject mods = new JsonObject();
            for (Module m : ModuleManager.getModules()) {
                mods.addProperty(m.getName(), m.isEnabled());
            }
            JsonObject root = new JsonObject();
            root.add("modules", mods);
            root.add("settings", saveSettings());
            Files.writeString(path(), GSON.toJson(root));
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] Could not save config: {}", t.toString());
        }
    }

    /**
     * Module settings, as a sibling of "modules" rather than nested inside it.
     *
     * "modules" maps a name straight to a boolean in every config written so
     * far. Turning those values into objects would have made every existing
     * config unreadable and reset everyone's enabled modules. A new key is
     * ignored by older builds and absent for older configs, which read as
     * defaults.
     */
    private static JsonObject saveSettings() {
        JsonObject all = new JsonObject();
        for (Module m : ModuleManager.getModules()) {
            if (!m.hasSettings()) continue;
            JsonObject one = new JsonObject();
            for (Setting s : m.getSettings()) {
                try { s.write(one); } catch (Throwable ignored) {}
            }
            all.add(m.getName(), one);
        }
        return all;
    }

    private static void loadSettings(JsonObject root) {
        if (!root.has("settings") || !root.get("settings").isJsonObject()) return;
        JsonObject all = root.getAsJsonObject("settings");
        for (Module m : ModuleManager.getModules()) {
            String key = Renames.savedKey(m.getName(), m.earlierNames(), all::has);
            if (!m.hasSettings() || key == null) continue;
            JsonObject one = all.getAsJsonObject(key);
            for (Setting s : m.getSettings()) {
                // Each setting reads independently and falls back to its own
                // default. One malformed value therefore costs that value only,
                // rather than aborting the loop and leaving every setting after
                // it at its default with nothing logged.
                try { s.read(one); } catch (Throwable ignored) {}
            }
        }
    }
}
