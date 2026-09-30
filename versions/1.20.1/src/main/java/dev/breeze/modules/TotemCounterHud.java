package dev.breeze.modules;

import dev.breeze.Category;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class TotemCounterHud extends AbstractHudModule {

    public TotemCounterHud() {
        super("Totem Counter", Category.HUD, "Counts totems of undying.", KEY_NONE, 4, 304);
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        if (mc.player == null) return;
        int count = 0;
        Inventory inv = mc.player.getInventory();
        for (int i = 0; i < InventoryHud.MAIN_SLOTS; i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(Items.TOTEM_OF_UNDYING)) count += s.getCount();
        }
        ItemStack off = mc.player.getOffhandItem();
        if (off.is(Items.TOTEM_OF_UNDYING)) count += off.getCount();
        line(g, font, "Totems: " + count, count > 0 ? 0xFF55FF55 : 0xFFFFFFFF);
    }
}
