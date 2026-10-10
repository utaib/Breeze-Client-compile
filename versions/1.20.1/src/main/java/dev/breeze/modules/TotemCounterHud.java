package dev.breeze.modules;

import dev.breeze.Category;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class TotemCounterHud extends AbstractHudModule {

    private final dev.breeze.settings.Setting.Bool icon =
            add(new dev.breeze.settings.Setting.Bool("icon", "Item icon", "Totem Counter", true));
    /** The totem Minecraft draws, made once (the item exists by the time a module draws). */
    private ItemStack totem;

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
        int color = count > 0 ? 0xFF55FF55 : 0xFFFFFFFF;
        if (icon.value) {
            if (totem == null) totem = new ItemStack(Items.TOTEM_OF_UNDYING);
            itemLine(g, font, totem, String.valueOf(count), color);
        } else {
            line(g, font, "Totems: " + count, color);
        }
    }
}
