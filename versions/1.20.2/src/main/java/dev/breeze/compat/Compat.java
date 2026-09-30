package dev.breeze.compat;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Minecraft calls whose shape differs between versions: this is the 1.20.2
 * form (see versions/1.20.1 for the base), and a version whose Minecraft
 * differs has its own copy of this file
 * in its versions/ folder (see gradle/version.gradle, "Sources"). The rest of
 * the mod calls these instead of Minecraft directly, so a Minecraft change
 * means one small file per version rather than every caller.
 *
 * Everything here is a plain call the compiler checks and the remapper
 * renames. (It once resolved these by reflection on Mojang names, which only
 * works in the development client: a player's install uses intermediary
 * names, so every lookup failed in real games.)
 */
public final class Compat {

    private Compat() {}

    /** Tear down the current level before connecting elsewhere. 1.20.2: disconnect. */
    public static void clearLevel(Minecraft mc) {
        mc.disconnect();
    }

    /** A ServerData for a direct connect. 1.20.2: (name, ip, Type). */
    public static ServerData serverData(String name, String address) {
        return new ServerData(name, address, ServerData.Type.OTHER);
    }

    /** Connect to a server the player chose, returning to parent on failure. */
    public static void connect(Screen parent, Minecraft mc, String address, ServerData data) {
        ConnectScreen.startConnecting(parent, mc, ServerAddress.parseString(address), data, false);
    }

    /**
     * Draw the player model standing on (cx, cy), looking along (lookX, lookY)
     * as 1.20.1's call took it. 1.20.2 takes a box around the model and the
     * mouse position instead, so both are derived: the box from the model's
     * proportions at this scale (vanilla's inventory box is 49 by 70 at scale
     * 30), and the mouse from the eye height 1.20.1 measured the look from.
     */
    public static void renderPlayerPreview(GuiGraphics g, int cx, int cy, int size,
                                           float lookX, float lookY, LivingEntity e) {
        if (g == null || e == null) return;
        try {
            int halfW = Math.round(size * 0.82f);
            int x1 = cx - halfW, x2 = cx + halfW;
            int y1 = cy - Math.round(size * 2.33f), y2 = cy;
            float mouseX = cx - lookX;
            float mouseY = cy - size * 1.67f - lookY;
            InventoryScreen.renderEntityInInventoryFollowsMouse(g, x1, y1, x2, y2, size, 0.0625f, mouseX, mouseY, e);
        } catch (Throwable ignored) {
            // A missing preview is cosmetic; never let it break the screen.
        }
    }

    /** A horse's jump strength, the value the jump height formula takes. */
    public static double horseJumpStrength(AbstractHorse horse) {
        return horse.getCustomJump();
    }

    /** The items stored in a shulker box item, in slot order; empty if none. */
    public static List<ItemStack> shulkerContents(ItemStack stack) {
        List<ItemStack> out = new ArrayList<>();
        CompoundTag tag = BlockItem.getBlockEntityData(stack);
        if (tag == null || !tag.contains("Items")) return out;
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, items);
        for (ItemStack s : items) if (!s.isEmpty()) out.add(s);
        return out;
    }

    /** Adds lines to every item tooltip. */
    public static void registerTooltips(BiConsumer<ItemStack, List<Component>> appender) {
        ItemTooltipCallback.EVENT.register((stack, flag, lines) -> appender.accept(stack, lines));
    }

    /** For a debug readout. */
    public static String status() {
        return "direct calls (1.20.2)";
    }
}
