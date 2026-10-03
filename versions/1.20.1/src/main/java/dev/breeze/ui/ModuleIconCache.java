package dev.breeze.ui;

import com.google.gson.JsonObject;
import dev.breeze.Module;
import dev.breeze.ModuleManager;
import dev.breeze.compat.Draw;
import dev.breeze.compat.Ids;
import dev.breeze.compat.Resources;
import dev.breeze.cosmetics.ImageData;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Each module's icon ({@link ModuleIcons}) found in the running game: the
 * texture it came from, the square of it to show, and the same file as a
 * data: URI for the web menu, which cannot load anything itself.
 *
 * Looked up once per module and kept for the session. Called on the game's
 * own thread only (the native menu while drawing, the bridge's client-thread
 * actions), as Minecraft's resource manager expects.
 */
public final class ModuleIconCache {

    /** One module's picture: where it is, which square of it, and the file as data. */
    public record Icon(ResourceLocation texture, ModuleIcons.Crop crop, String dataUri, String source) {}

    /** Item and GUI textures are a few hundred bytes; a resource pack's large one still fits. */
    private static final int MAX_BYTES = 256 * 1024;

    private static final Map<String, Optional<Icon>> CACHE = new ConcurrentHashMap<>();

    private ModuleIconCache() {}

    /** The module's icon, or null when none of its places to look exists in this game. */
    public static Icon get(String module) {
        return CACHE.computeIfAbsent(module, ModuleIconCache::find).orElse(null);
    }

    private static Optional<Icon> find(String module) {
        for (ModuleIcons.Source s : ModuleIcons.sources(module)) {
            ResourceLocation id = Ids.of(s.namespace(), s.path());
            byte[] png = Resources.read(id, MAX_BYTES);
            int[] size = ImageData.pngSize(png);
            if (size == null) continue;
            ModuleIcons.Crop crop = ModuleIcons.crop(s, size[0], size[1]);
            String uri = crop == null ? null : ImageData.dataUri(png);
            if (uri != null) return Optional.of(new Icon(id, crop, uri, s.path()));
        }
        return Optional.empty();
    }

    /** Draws the module's icon size x size at (x, y); false when it has none, so the caller can leave the space. */
    public static boolean draw(GuiGraphics g, String module, int x, int y, int size) {
        Icon icon = get(module);
        if (icon == null) return false;
        ModuleIcons.Crop c = icon.crop();
        Draw.blit(g, icon.texture(), x, y, size, size, c.x(), c.y(), c.size(), c.size(), c.sheetW(), c.sheetH());
        return true;
    }

    /** Every module's icon for the web menu: the file, and the square of it to show. */
    public static JsonObject json() {
        JsonObject icons = new JsonObject();
        for (Module m : ModuleManager.getModules()) {
            Icon icon = get(m.getName());
            if (icon == null) continue;
            ModuleIcons.Crop c = icon.crop();
            JsonObject o = new JsonObject();
            o.addProperty("src", icon.dataUri());
            o.addProperty("x", c.x());
            o.addProperty("y", c.y());
            o.addProperty("size", c.size());
            o.addProperty("w", c.sheetW());
            o.addProperty("h", c.sheetH());
            icons.add(m.getName(), o);
        }
        JsonObject out = new JsonObject();
        out.add("icons", icons);
        return out;
    }

    /** For the self-test: modules without an icon here, and those that needed a later place to look. */
    public static JsonObject report() {
        List<String> missing = new ArrayList<>();
        JsonObject fallbacks = new JsonObject();
        int found = 0;
        for (Module m : ModuleManager.getModules()) {
            Icon icon = get(m.getName());
            if (icon == null) {
                missing.add(m.getName());
                continue;
            }
            found++;
            List<ModuleIcons.Source> sources = ModuleIcons.sources(m.getName());
            if (!sources.isEmpty() && !sources.get(0).path().equals(icon.source())) {
                fallbacks.addProperty(m.getName(), icon.source());
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("modules", String.valueOf(ModuleManager.getModules().size()));
        o.addProperty("found", String.valueOf(found));
        o.addProperty("missing", String.join(",", missing));
        o.add("fallbacks", fallbacks);
        o.addProperty("pass", String.valueOf(missing.isEmpty()));
        return o;
    }
}
