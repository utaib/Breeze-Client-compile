package dev.breeze.modules;

import dev.breeze.Category;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public class ItemCounterHud extends AbstractHudModule {

    public ItemCounterHud() {
        super("Item Counter", Category.HUD, "Counts held item across inventory.", KEY_NONE, 4, 324);
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        if (mc.player == null) return;
        ItemStack held = mc.player.getMainHandItem();
        if (held.isEmpty()) {
            line(g, font, "Item: -");
            return;
        }
        Item type = held.getItem();
        int count = 0;
        for (ItemStack s : mc.player.getInventory().items) {
            if (s.getItem() == type) count += s.getCount();
        }
        for (ItemStack s : mc.player.getInventory().offhand) {
            if (s.getItem() == type) count += s.getCount();
        }
        line(g, font, held.getHoverName().getString() + ": " + count);
    }
}
