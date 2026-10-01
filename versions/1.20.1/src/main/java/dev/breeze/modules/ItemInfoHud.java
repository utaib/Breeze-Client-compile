package dev.breeze.modules;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import dev.breeze.Category;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

public class ItemInfoHud extends AbstractHudModule {

    public ItemInfoHud() {
        super("Item Info", Category.HUD, "Shows held item details.", KEY_NONE, 4, 184);
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        if (mc.player == null) return;
        ItemStack held = mc.player.getMainHandItem();
        if (held.isEmpty()) {
            line(g, font, "Item: -");
            return;
        }
        String s = held.getHoverName().getString();
        if (held.getCount() > 1) s += " x" + held.getCount();
        if (held.isDamageableItem()) {
            s += "  " + (held.getMaxDamage() - held.getDamageValue()) + "/" + held.getMaxDamage();
        }
        if (held.isEnchanted()) s += " [E]";
        line(g, font, s);
    }
}
