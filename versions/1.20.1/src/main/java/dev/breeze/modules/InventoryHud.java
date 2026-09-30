package dev.breeze.modules;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import dev.breeze.Category;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public class InventoryHud extends AbstractHudModule {

    /** The player inventory's main slots, hotbar included: 0 to 35 in every version. */
    static final int MAIN_SLOTS = 36;
    private static final int COLS = 9;
    private static final int SLOT = 18;

    public InventoryHud() {
        super("Inventory HUD", Category.HUD, "Mirrors your inventory onto the HUD.", KEY_NONE, 4, 4);
    }

    /** Top right until moved, where it always used to be. */
    @Override
    protected dev.breeze.hud.HudPlacement defaultPlacement() {
        return new dev.breeze.hud.HudPlacement(dev.breeze.hud.HudPlacement.H.RIGHT, dev.breeze.hud.HudPlacement.V.TOP, 4, 4);
    }

    @Override
    protected boolean drawsShapes() {
        return true;
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        // The size is known without a player, so the HUD editor can place the
        // grid before there is anything in it.
        bounds(COLS * SLOT, (MAIN_SLOTS / COLS) * SLOT);
        if (mc.player == null) return;
        // The 36 main slots (hotbar first) by index: the list behind them
        // stopped being public in Minecraft 1.21.5, getItem did not.
        Inventory inv = mc.player.getInventory();
        for (int i = 0; i < MAIN_SLOTS; i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty()) continue;
            int gx = x + (i % COLS) * SLOT;
            int gy = y + (i / COLS) * SLOT;
            g.renderItem(st, gx, gy);
            g.renderItemDecorations(mc.font, st, gx, gy);
        }
    }
}
