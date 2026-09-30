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

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        if (mc.player == null) return;
        // The 36 main slots (hotbar first) by index: the list behind them
        // stopped being public in Minecraft 1.21.5, getItem did not.
        Inventory inv = mc.player.getInventory();
        int startX = mc.getWindow().getGuiScaledWidth() - COLS * SLOT - 4;
        int startY = 4;
        for (int i = 0; i < MAIN_SLOTS; i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty()) continue;
            int gx = startX + (i % COLS) * SLOT;
            int gy = startY + (i / COLS) * SLOT;
                g.renderItem(st, gx, gy);
                g.renderItemDecorations(mc.font, st, gx, gy);
        }
    }
}
