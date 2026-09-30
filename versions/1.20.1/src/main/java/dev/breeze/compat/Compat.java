package dev.breeze.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.entity.LivingEntity;

/**
 * Minecraft calls whose shape differs between versions, written for 1.20.1.
 *
 * This used to resolve them by reflection on Mojang names ("clearLevel",
 * "net.minecraft.client.multiplayer.ServerData", "renderEntityInInventory-
 * FollowsMouse") so one source tree could serve several versions. That only
 * works in the development client. A player's install uses intermediary names
 * (ServerData is class_642 there), so every lookup failed in real games: joining
 * a friend's hosted world threw before connecting, and the Wardrobe's player
 * preview never drew. Each version module now compiles against exactly one
 * Minecraft, so these are plain calls the compiler checks and the remapper
 * renames. Other versions keep the same method signatures in their own copy.
 */
public final class Compat {

    private Compat() {}

    /** Tear down the current level before connecting elsewhere. Public on 1.20.1. */
    public static void clearLevel(Minecraft mc) {
        mc.clearLevel();
    }

    /** A ServerData for a direct connect. 1.20.1: (name, ip, lan). */
    public static Object serverData(String name, String address) {
        return new ServerData(name, address, false);
    }

    /**
     * Draw the player model in a preview box, centred on (cx, cy).
     * 1.20.1: (GuiGraphics, x, y, scale, lookX, lookY, LivingEntity).
     */
    public static void renderPlayerPreview(Object graphics, int cx, int cy, int size,
                                           float lookX, float lookY, Object entity) {
        if (!(graphics instanceof GuiGraphics g) || !(entity instanceof LivingEntity e)) return;
        try {
            InventoryScreen.renderEntityInInventoryFollowsMouse(g, cx, cy, size, lookX, lookY, e);
        } catch (Throwable ignored) {
            // A missing preview is cosmetic; never let it break the screen.
        }
    }

    /** For a debug readout. Every call is direct on this version. */
    public static String status() {
        return "direct calls (1.20.1)";
    }
}
