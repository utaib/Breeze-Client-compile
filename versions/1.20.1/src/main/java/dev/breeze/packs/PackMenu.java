package dev.breeze.packs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.breeze.compat.Links;
import dev.breeze.compat.PackIcons;
import dev.breeze.compat.PackOptions;
import dev.breeze.cosmetics.ImageData;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resource packs in the Breeze menu (packs.list, packs.apply,
 * packs.openFolder, and the native menu's Resource packs screen). Nothing
 * here loads packs: the list is Minecraft's own pack repository, and a
 * change goes through Minecraft's own options exactly as its Resource Packs
 * screen leaves them (PackOptions), so vanilla's screen, options.txt and the
 * launcher's resourcepacks folder all keep agreeing.
 */
public final class PackMenu {

    /** A picture larger than this is left out (Minecraft's own are a few KB). */
    private static final int ICON_BYTES = 128 * 1024;
    private static final int ICON_SIDE = 1024;

    /** Pictures by pack id: a data: URI, or "" for a pack without a usable one. */
    private static final Map<String, String> icons = new ConcurrentHashMap<>();

    private PackMenu() {}

    /** Every pack Minecraft knows, and the ones on, top first. Looks in the folder again first. */
    public static JsonObject json(Minecraft mc) {
        return json(mc, true);
    }

    /** As {@link #json(Minecraft)}; the native menu leaves the pictures out. */
    public static JsonObject json(Minecraft mc, boolean pictures) {
        PackRepository repo = mc.getResourcePackRepository();
        repo.reload();
        List<Pack> selected = new ArrayList<>(repo.getSelectedPacks());
        Set<String> on = new HashSet<>();
        for (Pack p : selected) on.add(p.getId());
        JsonArray packs = new JsonArray();
        for (Pack p : repo.getAvailablePacks()) packs.add(entry(p, on.contains(p.getId()), pictures));
        JsonArray order = new JsonArray();
        for (int i = selected.size() - 1; i >= 0; i--) order.add(selected.get(i).getId());
        JsonObject o = new JsonObject();
        o.add("packs", packs);
        o.add("selected", order);
        o.addProperty("inWorld", mc.level != null);
        return o;
    }

    private static JsonObject entry(Pack p, boolean on, boolean pictures) {
        JsonObject e = new JsonObject();
        e.addProperty("id", p.getId());
        e.addProperty("title", PackOrder.plain(p.getTitle().getString(), 80));
        e.addProperty("description", PackOrder.plain(p.getDescription().getString(), 240));
        e.addProperty("compatible", p.getCompatibility().isCompatible());
        // TOO_OLD, TOO_NEW or COMPATIBLE: made for an older or newer Minecraft.
        e.addProperty("compatibility", p.getCompatibility().name().toLowerCase(Locale.ROOT));
        e.addProperty("required", p.isRequired());
        e.addProperty("fixed", p.isFixedPosition());
        // The game's own and every mod's resources (Fabric gives each loaded
        // mod a fixed pack): the menu shows them folded, and never changes them.
        boolean locked = p.isRequired() || p.isFixedPosition();
        e.addProperty("locked", locked);
        e.addProperty("top", p.getDefaultPosition() == Pack.Position.TOP);
        e.addProperty("folder", p.getId().startsWith("file/"));
        e.addProperty("on", on);
        String icon = pictures && !locked ? icon(p) : "";
        if (icon.isEmpty()) e.add("picture", null);
        else e.addProperty("picture", icon);
        return e;
    }

    private static String icon(Pack p) {
        return icons.computeIfAbsent(p.getId(), id -> {
            try {
                byte[] b = PackIcons.read(p, ICON_BYTES);
                if (b == null || b.length > ICON_BYTES) return "";
                int[] size = ImageData.pngSize(b);
                if (size == null || size[0] > ICON_SIDE || size[1] > ICON_SIDE) return "";
                String uri = ImageData.dataUri(b);
                return uri == null ? "" : uri;
            } catch (Throwable t) {
                return "";
            }
        });
    }

    /**
     * Turns on the packs asked for, top first, and the rest off, keeping
     * Minecraft's rules (PackOrder). Resources reload when the list changed.
     */
    public static JsonObject apply(Minecraft mc, List<String> topFirst) {
        PackRepository repo = mc.getResourcePackRepository();
        Set<String> onNow = new HashSet<>(repo.getSelectedIds());
        List<PackOrder.Entry> entries = new ArrayList<>();
        for (Pack p : repo.getAvailablePacks()) {
            entries.add(new PackOrder.Entry(p.getId(), p.isRequired(), p.isFixedPosition(),
                    p.getDefaultPosition() == Pack.Position.TOP, onNow.contains(p.getId())));
        }
        List<String> wanted = PackOrder.selection(entries, topFirst);
        List<String> before = new ArrayList<>(repo.getSelectedIds());
        repo.setSelected(wanted);
        PackOptions.apply(mc, repo);
        boolean changed = !before.equals(new ArrayList<>(repo.getSelectedIds()));
        lastApplied = String.join(",", repo.getSelectedIds());
        JsonObject o = json(mc);
        o.addProperty("changed", changed);
        return o;
    }

    /** Opens Minecraft's resourcepacks folder (made if missing) in the system's file manager. */
    public static void openFolder(Minecraft mc) throws java.io.IOException {
        Path dir = mc.gameDirectory.toPath().resolve("resourcepacks");
        Files.createDirectories(dir);
        Links.open(dir.toUri());
    }

    private static volatile String lastApplied = "";

    /** For the self-test: the repository's selection right after the last apply, bottom first. */
    public static String lastApplied() { return lastApplied; }
}
