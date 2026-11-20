package com.terraformersmc.modmenu.api;

import dev.breeze.integrations.FakeScreen;

import java.util.Map;

/**
 * Test stand-in with the shape of Mod Menu's published API
 * (TerraformersMC/ModMenu, src/main/java/com/terraformersmc/modmenu/api),
 * with Minecraft's Screen replaced by FakeScreen.
 */
public interface ModMenuApi {
    static FakeScreen createModsScreen(FakeScreen previous) {
        return new FakeScreen("modmenu:mods", previous);
    }

    default ConfigScreenFactory<?> getModConfigScreenFactory() {
        return screen -> null;
    }

    default Map<String, ConfigScreenFactory<?>> getProvidedConfigScreenFactories() {
        return Map.of();
    }
}
