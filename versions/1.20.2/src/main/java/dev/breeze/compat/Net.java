package dev.breeze.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/**
 * Leaving a world and joining a server. These calls change shape between
 * Minecraft versions, so the rest of the mod calls them here, and versions/
 * has a copy of this file per change. This is the 1.20.2 form.
 *
 * Everything here is a plain call the compiler checks and the remapper
 * renames. (Breeze once resolved these by reflection on Mojang names, which
 * only works in the development client: a player's install uses intermediary
 * names, so every lookup failed in real games.)
 */
public final class Net {

    private Net() {}

    /** Tear down the current level before connecting elsewhere. */
    public static void clearLevel(Minecraft mc) {
        mc.disconnect();
    }

    /** A ServerData for a direct connect. */
    public static ServerData serverData(String name, String address) {
        return new ServerData(name, address, ServerData.Type.OTHER);
    }

    /** Connect to a server the player chose, returning to parent on failure. */
    public static void connect(Screen parent, Minecraft mc, String address, ServerData data) {
        ConnectScreen.startConnecting(parent, mc, ServerAddress.parseString(address), data, false);
    }
}
