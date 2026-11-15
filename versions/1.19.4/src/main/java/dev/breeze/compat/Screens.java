package dev.breeze.compat;

import net.minecraft.client.Minecraft;

import net.minecraft.client.gui.screens.OptionsScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.LivingEntity;

/**
 * Vanilla screens Breeze opens or draws from. versions/ has a copy of this
 * file per change; this is the 1.19.4 form (the preview draws with a PoseStack).
 */
public final class Screens {

    private Screens() {}

    /**
     * Draw the player model standing on (cx, cy) at a scale of size, looking
     * along (lookX, lookY) from its eyes.
     */
    public static void renderPlayerPreview(GuiGraphics g, int cx, int cy, int size,
                                           float lookX, float lookY, LivingEntity e) {
        if (g == null || e == null) return;
        try {
            InventoryScreen.renderEntityInInventoryFollowsMouse(g.pose(), cx, cy, size, lookX, lookY, e);
        } catch (Throwable ignored) {
            // A missing preview is cosmetic; never let it break the screen.
        }
    }

    /** Minecraft's Options screen, returning to parent. */
    public static Screen options(Screen parent, Minecraft mc) {
        return new OptionsScreen(parent, mc.options);
    }

    public static boolean isOptions(Screen screen) {
        return screen instanceof OptionsScreen;
    }
}
