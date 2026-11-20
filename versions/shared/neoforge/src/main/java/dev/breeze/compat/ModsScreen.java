package dev.breeze.compat;

import net.minecraft.client.gui.screens.Screen;

/** NeoForge's own list of mods (up to 26.1; shared/neoforge-26.2 has none). */
final class ModsScreen {

    static final boolean AVAILABLE = true;

    private ModsScreen() {}

    static Screen open(Screen parent) {
        return new net.neoforged.neoforge.client.gui.ModListScreen(parent);
    }
}
