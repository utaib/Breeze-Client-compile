package dev.breeze.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minecraft's own pictures that Breeze draws outside a module card: the empty
 * armour slots, a container slot, the HUD's armour icon and the mob effect
 * icons. Each name lists the places to look in the running game's textures,
 * best first, in {@link ModuleIcons#parse} form. Several moved between
 * versions (1.20.2 made the HUD icons sprites, a later version the empty
 * slots), so the first that exists in this game is the one drawn, and a
 * resource pack's version of it wins as it does for Minecraft.
 *
 * Nothing here is a copy: these are paths into the game's own assets.
 */
public final class GameTextures {

    public static final String SLOT = "slot";
    public static final String EMPTY_HELMET = "slot.helmet";
    public static final String EMPTY_CHESTPLATE = "slot.chestplate";
    public static final String EMPTY_LEGGINGS = "slot.leggings";
    public static final String EMPTY_BOOTS = "slot.boots";
    public static final String EMPTY_SHIELD = "slot.shield";
    public static final String ARMOR_ICON = "hud.armor";

    private static final Map<String, List<ModuleIcons.Source>> TEXTURES = new LinkedHashMap<>();

    static {
        // One slot of the survival inventory picture: 18 by 18 at (7, 83) of
        // its 256 square sheet, the same in every version.
        put(SLOT, "gui/container/inventory#7,83,18");
        put(EMPTY_HELMET, "gui/sprites/container/slot/helmet", "item/empty_armor_slot_helmet");
        put(EMPTY_CHESTPLATE, "gui/sprites/container/slot/chestplate", "item/empty_armor_slot_chestplate");
        put(EMPTY_LEGGINGS, "gui/sprites/container/slot/leggings", "item/empty_armor_slot_leggings");
        put(EMPTY_BOOTS, "gui/sprites/container/slot/boots", "item/empty_armor_slot_boots");
        put(EMPTY_SHIELD, "gui/sprites/container/slot/shield", "item/empty_armor_slot_shield");
        // The full armour point above the hotbar: a sprite from 1.20.2, a
        // square of gui/icons.png (34, 9, 9 by 9) before.
        put(ARMOR_ICON, "gui/sprites/hud/armor_full", "gui/icons#34,9,9");
    }

    private GameTextures() {}

    /** Where to look for one named picture, best first; empty for an unknown name. */
    public static List<ModuleIcons.Source> sources(String name) {
        List<ModuleIcons.Source> s = TEXTURES.get(name);
        return s == null ? List.of() : s;
    }

    /** Every named picture. */
    public static List<String> names() {
        return List.copyOf(TEXTURES.keySet());
    }

    /**
     * A vanilla mob effect's icon, from its translation key
     * ("effect.minecraft.speed"); empty for another namespace or a key that
     * is not an effect's, so the caller draws text alone.
     */
    public static List<ModuleIcons.Source> effect(String descriptionId) {
        String prefix = "effect.minecraft.";
        if (descriptionId == null || !descriptionId.startsWith(prefix)) return List.of();
        String id = descriptionId.substring(prefix.length());
        if (id.isEmpty() || !id.matches("[a-z0-9_]+")) return List.of();
        List<ModuleIcons.Source> out = new ArrayList<>(2);
        out.add(ModuleIcons.parse("mob_effect/" + id));
        out.add(ModuleIcons.parse("gui/sprites/mob_effect/" + id));
        return List.copyOf(out);
    }

    private static void put(String name, String... specs) {
        List<ModuleIcons.Source> list = new ArrayList<>(specs.length);
        for (String spec : specs) list.add(ModuleIcons.parse(spec));
        TEXTURES.put(name, List.copyOf(list));
    }
}
