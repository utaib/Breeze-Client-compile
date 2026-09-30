package dev.breeze.modules;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import dev.breeze.Category;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import java.util.List;

public class InventoryHud extends AbstractHudModule {

    private static final int COLS = 9;
    private static final int SLOT = 18;

    public InventoryHud() {
        super("Inventory HUD", Category.HUD, "Mirrors your inventory onto the HUD.", KEY_NONE, 4, 4);
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        if (mc.player == null) return;
        List<ItemStack> items = mc.player.getInventory().items;
        int startX = mc.getWindow().getGuiScaledWidth() - COLS * SLOT - 4;
        int startY = 4;
        for (int i = 0; i < items.size(); i++) {
            ItemStack st = items.get(i);
            if (st.isEmpty()) continue;
            int gx = startX + (i % COLS) * SLOT;
            int gy = startY + (i / COLS) * SLOT;
                g.renderItem(st, gx, gy);
                g.renderItemDecorations(mc.font, st, gx, gy);
        }
    }
}
