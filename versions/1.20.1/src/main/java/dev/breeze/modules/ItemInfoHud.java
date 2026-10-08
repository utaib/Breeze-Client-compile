package dev.breeze.modules;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import dev.breeze.Category;
import dev.breeze.hud.HudHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

public class ItemInfoHud extends AbstractHudModule {

    private final dev.breeze.settings.Setting.Bool icon =
            add(new dev.breeze.settings.Setting.Bool("icon", "Item icon", "Item Info", true));

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
        // With the picture, its count, durability bar and enchantment glint
        // are Minecraft's own; the numbers stay in the text.
        if (!icon.value && held.getCount() > 1) s += " x" + held.getCount();
        if (held.isDamageableItem()) {
            s += "  " + (held.getMaxDamage() - held.getDamageValue()) + "/" + held.getMaxDamage();
        }
        if (!icon.value && held.isEnchanted()) s += " [E]";
        if (icon.value) itemLine(g, font, held, s, HudHelper.WHITE);
        else line(g, font, s);
    }
}
