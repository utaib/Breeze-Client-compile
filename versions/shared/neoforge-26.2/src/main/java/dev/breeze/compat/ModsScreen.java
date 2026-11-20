package dev.breeze.compat;

import net.minecraft.client.gui.screens.Screen;

/**
 * NeoForge 26.2 and 26.3 have no list of mods of their own
 * (net.neoforged.neoforge.client.gui.ModListScreen is gone), so Breeze offers
 * none; each mod's settings screen is still offered.
 */
final class ModsScreen {

    static final boolean AVAILABLE = false;

    private ModsScreen() {}

    static Screen open(Screen parent) {
        return null;
    }
}
