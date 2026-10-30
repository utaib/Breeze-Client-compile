package dev.breeze.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.OptionsScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.LivingEntity;

/**
 * Vanilla screens Breeze opens or draws from. versions/ has a copy of this
 * file per change; this is the 26.3 form: the options screen takes the parent
 * and the options again, as before 26.1.
 */
public final class Screens {

    private Screens() {}

    /**
     * Draw the player model standing on (cx, cy) at a scale of size, looking
     * along (lookX, lookY) from its eyes. From 1.20.2 Minecraft takes a box
     * around the model and the mouse position instead, so both are derived:
     * the box from the model's proportions at this scale (vanilla's inventory
     * box is 49 by 70 at scale 30), the mouse from the eye height.
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

    /** Minecraft's Options screen, returning to parent. */
    public static Screen options(Screen parent, Minecraft mc) {
        return new OptionsScreen(parent, mc.options);
    }

    public static boolean isOptions(Screen screen) {
        return screen instanceof OptionsScreen;
    }
}
