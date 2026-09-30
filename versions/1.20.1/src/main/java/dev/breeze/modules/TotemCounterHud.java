package dev.breeze.modules;

import dev.breeze.Category;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
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
        for (ItemStack s : mc.player.getInventory().items) {
            if (s.is(Items.TOTEM_OF_UNDYING)) count += s.getCount();
        }
        for (ItemStack s : mc.player.getInventory().offhand) {
            if (s.is(Items.TOTEM_OF_UNDYING)) count += s.getCount();
        }
        line(g, font, "Totems: " + count, count > 0 ? 0xFF55FF55 : 0xFFFFFFFF);
    }
}
