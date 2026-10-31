package dev.breeze.ui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.breeze.BreezeClient;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;

public final class MenuBg {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static boolean useBreeze = true;

    private MenuBg() {}

    public static boolean useBreeze() {
        return useBreeze;
    }

    public static void toggle() {
        useBreeze = !useBreeze;
        save();
    }

    private static Path path() {
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("config");
        try {
            Files.createDirectories(dir);
        } catch (Throwable ignored) {}
        return dir.resolve("breeze_menu_bg.json");
    }

    public static void load() {
        try {
            Path p = path();
            if (!Files.exists(p)) return;
            JsonObject root = GSON.fromJson(Files.readString(p), JsonObject.class);
            if (root != null && root.has("breeze_background")) useBreeze = root.get("breeze_background").getAsBoolean();
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] menu bg load failed: {}", t.toString());
        }
    }

    public static void save() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("breeze_background", useBreeze);
            Files.writeString(path(), GSON.toJson(root));
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] menu bg save failed: {}", t.toString());
        }
    }
}
