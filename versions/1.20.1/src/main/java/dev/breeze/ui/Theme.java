package dev.breeze.ui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.breeze.BreezeClient;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

public final class Theme {

    // Defaults now come from the launcher palette so the mod ships looking
    // like Breeze out of the box. Both stay user-adjustable in Theme settings.
    public static int primary = Palette.ACCENT;
    public static int secondary = Palette.lerp(Palette.ACCENT, 0xFFFFFFFF, 0.25f);
    public static int bgAlpha = 0xC8;

    /**
     * Which interface opens when the player asks for Breeze.
     *
     * AUTO keeps the old behaviour, preferring the web interface wherever MCEF
     * has initialised. NATIVE and WEB are the player saying so explicitly, and
     * are honoured on every route into the menu rather than only the keybind.
     *
     * A preference for WEB on a version with no MCEF build is not an error and
     * is not rewritten: the mod falls back for that session and the choice is
     * still there if they later play a version that can honour it. Thirty of
     * the forty supported Minecraft versions have no embedded browser at all
     * (docs/MOD_VERSION_MATRIX.md), so this has to degrade quietly.
     */
    public enum UiMode { AUTO, NATIVE, WEB }

    public static UiMode uiMode = UiMode.AUTO;

    private static final Set<String> FAVORITES = new LinkedHashSet<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path path;

    private Theme() {}

    public static int primary() {
        return primary;
    }

    public static int secondary() {
        return secondary;
    }

    public static int accent(float t) {
        return lerp(primary, secondary, t);
    }

    public static int panelBg() {
        // The launcher's surface tone, not the old #14141C.
        return ((bgAlpha & 0xFF) << 24) | (Palette.SURFACE & 0x00FFFFFF);
    }

    public static int cardBg() {
        return ((Math.min(255, bgAlpha + 18) & 0xFF) << 24) | 0x1E1E2A;
    }

    public static int cardHover() {
        return ((Math.min(255, bgAlpha + 34) & 0xFF) << 24) | 0x2A2A3A;
    }

    public static boolean isFavorite(String name) {
        return FAVORITES.contains(name);
    }

    public static void toggleFavorite(String name) {
        if (!FAVORITES.remove(name)) FAVORITES.add(name);
        save();
    }

    public static int lerp(int a, int b, float t) {
        if (t < 0) t = 0;
        if (t > 1) t = 1;
        int aa = (a >>> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int ca = (int) (aa + (ba - aa) * t);
        int cr = (int) (ar + (br - ar) * t);
        int cg = (int) (ag + (bg - ag) * t);
        int cb = (int) (ab + (bb - ab) * t);
        return (ca << 24) | (cr << 16) | (cg << 8) | cb;
    }

    public static int withAlpha(int color, int a) {
        return (color & 0x00FFFFFF) | ((a & 0xFF) << 24);
    }

    public static int channel(int color, int shift) {
        return (color >> shift) & 0xFF;
    }

    public static int setChannel(int color, int shift, int value) {
        value = Math.max(0, Math.min(255, value));
        return (color & ~(0xFF << shift)) | (value << shift);
    }

    public static Path path() {
        if (path == null) {
            Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("config");
            try {
                Files.createDirectories(dir);
            } catch (Throwable ignored) {}
            path = dir.resolve("breeze_theme.json");
        }
        return path;
    }

    public static void load() {
        try {
            Path p = path();
            if (!Files.exists(p)) return;
            JsonObject root = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            if (root.has("primary")) primary = root.get("primary").getAsInt();
            if (root.has("secondary")) secondary = root.get("secondary").getAsInt();
            if (root.has("bgAlpha")) bgAlpha = root.get("bgAlpha").getAsInt();
            if (root.has("uiMode")) {
                // An unrecognised value falls back to AUTO rather than throwing:
                // a hand-edited config should not stop the mod loading.
                try {
                    uiMode = UiMode.valueOf(root.get("uiMode").getAsString());
                } catch (IllegalArgumentException unknown) {
                    uiMode = UiMode.AUTO;
                }
            }
            FAVORITES.clear();
            if (root.has("favorites")) {
                JsonArray arr = root.getAsJsonArray("favorites");
                for (JsonElement e : arr) FAVORITES.add(e.getAsString());
            }
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] theme load failed: {}", t.toString());
        }
    }

    public static void save() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("primary", primary);
            root.addProperty("secondary", secondary);
            root.addProperty("bgAlpha", bgAlpha);
            root.addProperty("uiMode", uiMode.name());
            JsonArray favs = new JsonArray();
            for (String s : FAVORITES) favs.add(s);
            root.add("favorites", favs);
            Files.writeString(path(), GSON.toJson(root));
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] theme save failed: {}", t.toString());
        }
    }
}
