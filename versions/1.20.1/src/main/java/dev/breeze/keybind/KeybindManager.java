package dev.breeze.keybind;


import com.mojang.blaze3d.platform.InputConstants;
import dev.breeze.BreezeClient;
import dev.breeze.Module;
import dev.breeze.ModuleManager;
import dev.breeze.config.BreezeConfig;
import dev.breeze.web.WebInit;
import dev.breeze.compat.Keys;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import dev.breeze.ui.BreezeUi;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.resources.language.I18n;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public final class KeybindManager {

    private static final Map<Module, KeyMapping> bindings = new HashMap<>();
    private static KeyMapping menuKey;
    private static KeyMapping musicKey;
    /** Debug-only escape hatch: -Dbreeze.webmenu=false forces the native menu. */
    private static final boolean WEB_MENU_DISABLED = "false".equalsIgnoreCase(System.getProperty("breeze.webmenu", "true"));

    private KeybindManager() {}

    public static void registerAll() {
        for (Module m : ModuleManager.getModules()) {
            try {
                KeyMapping km = Keys.register(
                        "key.breeze." + m.getName().toLowerCase(java.util.Locale.ROOT).replace(' ', '_'),
                        m.getDefaultKey());
                m.setKeyMapping(km);
                bindings.put(m, km);
            } catch (Throwable t) {
                BreezeClient.LOGGER.warn("[Breeze] Could not register keybind for {}: {}", m.getName(), t.toString());
            }
        }
        try {
            menuKey = Keys.register("key.breeze.menu", InputConstants.KEY_RSHIFT);
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] Could not register menu keybind: {}", t.toString());
        }
        try {
            // M: Xaero's World Map uses it too; players who have both rebind
            // one of them in Controls.
            musicKey = Keys.register("key.breeze.music", InputConstants.KEY_M);
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] Could not register the music keybind: {}", t.toString());
        }
    }

    public static void handleTicks(Minecraft mc) {
        try {
            if (menuKey != null && menuKey.consumeClick()) {
                // Every route into the menu goes through BreezeUi so the
                // keybind and the title and pause buttons cannot disagree about
                // which interface the player asked for.
                BreezeUi.open(Minecraft.getInstance());
            }
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] Could not open menu: {}", t.toString());
        }
        try {
            if (musicKey != null && musicKey.consumeClick()) dev.breeze.music.Music.open(mc);
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] Could not open the music player: {}", t.toString());
        }
        for (Map.Entry<Module, KeyMapping> e : bindings.entrySet()) {
            try {
                if (e.getValue().consumeClick()) {
                    Module m = e.getKey();
                    m.toggle();
                    BreezeConfig.save();
                    notify(m.getName() + ": " + (m.isEnabled() ? "ON" : "OFF"));
                }
            } catch (Throwable ignored) {}
        }
    }

    /** For the self-test: the key the Breeze menu binding is on, and whether it is held now. */
    public static String menuKeyName() {
        return menuKey == null ? "unregistered" : menuKey.getTranslatedKeyMessage().getString();
    }

    public static boolean menuKeyDown() {
        return menuKey != null && menuKey.isDown();
    }

    /** For the self-test: the other bindings on the menu binding's key, by name. */
    public static String sharingMenuKey(Minecraft mc) {
        if (menuKey == null || menuKey.isUnbound() || mc.options == null) return "";
        StringBuilder out = new StringBuilder();
        for (KeyMapping other : mc.options.keyMappings) {
            if (other != menuKey && menuKey.same(other)) out.append(out.length() > 0 ? "," : "").append(other.getName());
        }
        return out.toString();
    }

    // ── Keys in the web menu (keys.list, keys.capture, keys.reset) ──────────

    /** The binding the menu is waiting for a key for, by id; null when none. */
    private static String capturing;

    /** Breeze's own bindings by id (their translation keys): the menu, music, then modules by name. */
    private static Map<String, KeyMapping> breezeKeys() {
        Map<String, KeyMapping> out = new LinkedHashMap<>();
        if (menuKey != null) out.put(menuKey.getName(), menuKey);
        if (musicKey != null) out.put(musicKey.getName(), musicKey);
        bindings.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<Module, KeyMapping> e) -> e.getKey().getName(), String.CASE_INSENSITIVE_ORDER))
                .forEach(e -> out.put(e.getValue().getName(), e.getValue()));
        return out;
    }

    private static Module moduleOf(KeyMapping km) {
        for (Map.Entry<Module, KeyMapping> e : bindings.entrySet()) if (e.getValue() == km) return e.getKey();
        return null;
    }

    /**
     * Every Breeze binding with its key, its default, and the other bindings
     * (Minecraft's or other mods') on the same key, as Minecraft's Controls
     * marks them.
     */
    public static JsonObject keysJson(Minecraft mc) {
        JsonArray list = new JsonArray();
        for (Map.Entry<String, KeyMapping> e : breezeKeys().entrySet()) {
            KeyMapping km = e.getValue();
            Module m = moduleOf(km);
            JsonObject o = new JsonObject();
            o.addProperty("id", e.getKey());
            o.addProperty("label", m != null ? m.getName() : km == menuKey ? "Open the Breeze menu" : "Music player");
            if (m != null) o.addProperty("module", m.getName());
            o.addProperty("key", km.isUnbound() ? "" : km.getTranslatedKeyMessage().getString());
            o.addProperty("isDefault", km.isDefault());
            InputConstants.Key def = km.getDefaultKey();
            o.addProperty("defaultKey", def == null || def.equals(InputConstants.UNKNOWN) ? "" : def.getDisplayName().getString());
            JsonArray conflicts = new JsonArray();
            if (!km.isUnbound() && mc.options != null) {
                for (KeyMapping other : mc.options.keyMappings) {
                    if (other != km && km.same(other)) conflicts.add(I18n.get(other.getName()));
                }
            }
            o.add("conflicts", conflicts);
            list.add(o);
        }
        JsonObject out = new JsonObject();
        out.add("keys", list);
        if (capturing != null) out.addProperty("capturing", capturing);
        return out;
    }

    /** Waits for the next key pressed in the Breeze menu, for this binding. False for an unknown id. */
    public static boolean capture(String id) {
        if (!breezeKeys().containsKey(id)) return false;
        capturing = id;
        return true;
    }

    public static void cancelCapture() {
        capturing = null;
    }

    public static String capturing() {
        return capturing;
    }

    /**
     * A key pressed in the menu while it waits (key code and scan code as
     * Minecraft read them): Escape cancels, any other key becomes the
     * binding, as in Minecraft's Controls. Returns the binding's id.
     */
    public static String takeKey(Minecraft mc, int key, int scanCode) {
        String id = capturing;
        capturing = null;
        if (id == null || key == InputConstants.KEY_ESCAPE) return id;
        KeyMapping km = breezeKeys().get(id);
        if (km != null) apply(mc, km, Keys.keyOf(key, scanCode));
        return id;
    }

    /** Back to the binding's default key, or to no key. False for an unknown id. */
    public static boolean reset(Minecraft mc, String id, boolean none) {
        KeyMapping km = breezeKeys().get(id);
        if (km == null) return false;
        apply(mc, km, none ? InputConstants.UNKNOWN : km.getDefaultKey());
        return true;
    }

    /** What Minecraft's Controls does: set, rebuild the key lookup, save options.txt. */
    private static void apply(Minecraft mc, KeyMapping km, InputConstants.Key key) {
        km.setKey(key);
        KeyMapping.resetMapping();
        mc.options.save();
    }

    private static void notify(String msg) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                dev.breeze.compat.Game.message(mc, Component.literal("§b[Breeze]§r " + msg));
            } else {
                BreezeClient.LOGGER.info("[Breeze] {}", msg);
            }
        } catch (Throwable t) {
            BreezeClient.LOGGER.info("[Breeze] {}", msg);
        }
    }
}
