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

import java.util.HashMap;
import java.util.Map;

public final class KeybindManager {

    private static final Map<Module, KeyMapping> bindings = new HashMap<>();
    private static KeyMapping menuKey;
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
