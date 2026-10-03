package dev.breeze.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Minecraft picture that stands for each Breeze module: a clock for the
 * Stopwatch, a redstone torch for FPS, the hunger drumstick for Saturation.
 *
 * Every picture is read from the running game's own assets, so it is the
 * texture of that Minecraft version, and a resource pack that changes it
 * changes the icon too. Nothing is copied into Breeze's jar.
 *
 * Each module lists places to look, best first. Textures move between
 * versions (the hearts and the drumstick were cut from one shared image,
 * gui/icons.png, until 1.20.2 split it into sprites), so a later entry is the
 * fallback for versions where an earlier one does not exist. The self-test
 * checks on every version that each module found one.
 */
public final class ModuleIcons {

    /**
     * One texture in the game's assets. A crop, when there is one, is a square
     * of a shared sheet given in the units of its 256-pixel vanilla size, so it
     * still lands on the right picture in a resource pack's larger sheet.
     */
    public record Source(String namespace, String path, int cropX, int cropY, int cropSize) {
        public boolean cropped() {
            return cropSize > 0;
        }
    }

    /** The square of the texture to show, in the texture's own pixels. */
    public record Crop(int x, int y, int size, int sheetW, int sheetH) {}

    /** The width, in pixels, of the vanilla sheets crops are given against. */
    private static final int SHEET = 256;

    private static final Map<String, List<Source>> ICONS = new LinkedHashMap<>();

    static {
        // HUD
        put("Armor Bar", "item/iron_chestplate");
        put("Armor Status", "item/diamond_chestplate");
        put("Attack Indicator", "item/iron_sword");
        put("Block Indicator", "block/grass_block_side");
        put("CPS", "block/target_side");
        put("Combo Counter", "item/golden_sword");
        put("Coordinates", "item/map");
        put("Day Counter", "block/sunflower_front");
        put("Death Info", "item/bone");
        put("Direction", "item/compass_00", "item/compass");
        put("FPS", "block/redstone_torch");
        put("Hearts", "gui/sprites/hud/heart/full", "gui/icons#52,0,9", "item/golden_apple");
        put("Held Item", "item/iron_pickaxe");
        put("Horse Stats", "item/saddle");
        put("Inventory HUD", "item/chest_minecart");
        put("Item Counter", "item/emerald");
        put("Item Despawn Timer", "item/rotten_flesh");
        put("Item Info", "item/written_book");
        put("Keystrokes", "block/note_block");
        put("Mouse Strokes", "item/lead");
        put("Pack Display", "item/item_frame");
        put("Ping", "item/ender_pearl");
        put("Playtime", "item/experience_bottle");
        put("Potion Effects", "item/brewing_stand");
        put("Reach Display", "item/trident");
        put("Recording Indicator", "item/music_disc_cat");
        put("Saturation", "gui/sprites/hud/food_full", "gui/icons#52,27,9", "item/cooked_chicken");
        put("Server Address", "item/oak_sign");
        put("Speed Meter", "item/sugar");
        put("Stopwatch", "item/clock_00", "item/clock");
        put("System Resources", "item/comparator");
        put("TNT Timer", "block/tnt_side");
        put("TPS", "item/repeater");
        put("Time", "block/daylight_detector_top");
        put("Totem Counter", "item/totem_of_undying");
        put("Waypoint", "block/lodestone_top", "item/compass_16", "item/compass");
        // Utility
        put("Auto Hide HUD", "item/phantom_membrane");
        put("Auto Perspective", "item/firework_rocket");
        put("Custom Advancements", "item/knowledge_book");
        put("Drop Prevention", "item/slime_ball");
        put("Fullbright", "block/glowstone");
        put("Keybind Search", "block/tripwire_hook");
        put("Nickname Hider", "block/tinted_glass", "block/black_stained_glass");
        put("Perspective", "item/ender_eye");
        put("Reconnect", "item/chain", "item/iron_chain");
        put("Scoreboard", "item/birch_sign");
        put("Screenshots", "item/painting");
        put("Shulker Tooltips", "item/shulker_shell");
        put("Sound Filter", "block/jukebox_top");
        put("Subtitles Toggle", "item/bell");
        put("Time Changer", "item/clock_16", "item/clock");
        put("Title Tweaker", "item/paper");
        put("Toast Control", "item/bread");
        put("Toggle Sneak", "item/chainmail_boots");
        put("Toggle Sprint", "item/rabbit_foot");
        put("Tooltips", "item/book");
        put("UI Scaling", "item/glow_item_frame", "item/item_frame");
        put("World Backups", "block/barrel_top");
        // Visual
        put("Block Overlay", "block/glass");
        put("Boss Bar", "item/nether_star");
        put("Custom Cape", "item/elytra");
        put("Custom Crosshair", "item/crossbow_standby");
        put("Damage Indicator", "item/redstone");
        put("Dark Mode", "item/ink_sac");
        put("FOV Changes", "item/feather");
        put("Friend Glow", "item/spectral_arrow");
        put("Hit Indicator", "item/arrow");
        put("Hitboxes", "block/scaffolding_top");
        put("Item Physics", "item/apple");
        put("Item Scale", "item/diamond");
        put("Light Level Overlay", "block/torch");
        put("Loot Beams", "block/beacon");
        put("Low Fire", "item/fire_charge");
        put("Mob Overlay", "item/spider_eye");
        put("Name Tags", "item/name_tag");
        put("No Hurt Cam", "item/golden_apple");
        put("No View Bobbing", "item/carrot_on_a_stick");
        put("Player Model", "item/armor_stand");
        put("Smooth Camera", "item/honey_bottle");
        put("Zoom", "item/spyglass");
        // Chat
        put("Auto Text", "item/writable_book");
    }

    private ModuleIcons() {}

    /** Where to look for a module's icon, best first; empty for a module with none. */
    public static List<Source> sources(String module) {
        List<Source> s = ICONS.get(module);
        return s == null ? List.of() : s;
    }

    /** Every module that has an icon. */
    public static Set<String> modules() {
        return Collections.unmodifiableSet(ICONS.keySet());
    }

    /**
     * Parses one entry: a texture under minecraft:textures/ without its
     * extension, such as "item/clock_00", optionally with "#x,y,size" for a
     * square of a shared sheet.
     */
    public static Source parse(String spec) {
        String path = spec;
        int cx = 0, cy = 0, cs = 0;
        int hash = spec.indexOf('#');
        if (hash >= 0) {
            path = spec.substring(0, hash);
            String[] n = spec.substring(hash + 1).split(",");
            if (n.length != 3) throw new IllegalArgumentException("crop needs x,y,size: " + spec);
            cx = Integer.parseInt(n[0].trim());
            cy = Integer.parseInt(n[1].trim());
            cs = Integer.parseInt(n[2].trim());
            if (cx < 0 || cy < 0 || cs <= 0 || cx + cs > SHEET || cy + cs > SHEET) {
                throw new IllegalArgumentException("crop outside the sheet: " + spec);
            }
        }
        if (path.isEmpty() || path.startsWith("/") || path.contains("..") || !path.equals(path.toLowerCase())) {
            throw new IllegalArgumentException("not a texture path: " + spec);
        }
        return new Source("minecraft", "textures/" + path + ".png", cx, cy, cs);
    }

    /**
     * The square to show of a texture that is w by h pixels, or null when the
     * picture is not where it should be (a sheet smaller than vanilla's, or
     * too small for the crop).
     * Without a crop it is the top square: a texture taller than wide is an
     * animation strip, and its first frame is the picture.
     */
    public static Crop crop(Source s, int w, int h) {
        if (w <= 0 || h <= 0) return null;
        if (!s.cropped()) {
            int size = Math.min(w, h);
            return new Crop(0, 0, size, w, h);
        }
        // A sheet smaller than the vanilla one would shrink the picture to a
        // few pixels; the next place to look is better than that.
        if (w < SHEET) return null;
        float scale = w / (float) SHEET;
        int x = Math.round(s.cropX() * scale);
        int y = Math.round(s.cropY() * scale);
        int size = Math.max(1, Math.round(s.cropSize() * scale));
        if (x + size > w || y + size > h) return null;
        return new Crop(x, y, size, w, h);
    }

    private static void put(String module, String... specs) {
        List<Source> list = new ArrayList<>(specs.length);
        for (String spec : specs) list.add(parse(spec));
        ICONS.put(module, List.copyOf(list));
    }
}
