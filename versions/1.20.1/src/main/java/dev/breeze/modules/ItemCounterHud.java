package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.hud.HudHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.Item;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public class ItemCounterHud extends AbstractHudModule {

    private final dev.breeze.settings.Setting.Bool icon =
            add(new dev.breeze.settings.Setting.Bool("icon", "Item icon", "Item Counter", true));
    /** One of the held item, so the picture shows the item and not the stack's count. */
    private ItemStack shown = ItemStack.EMPTY;

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
        Inventory inv = mc.player.getInventory();
        for (int i = 0; i < InventoryHud.MAIN_SLOTS; i++) {
            ItemStack s = inv.getItem(i);
            if (s.getItem() == type) count += s.getCount();
        }
        ItemStack off = mc.player.getOffhandItem();
        if (off.getItem() == type) count += off.getCount();
        if (icon.value) {
            if (shown.getItem() != type) {
                shown = held.copy();
                shown.setCount(1);
            }
            itemLine(g, font, shown, String.valueOf(count), HudHelper.WHITE);
            return;
        }
        line(g, font, held.getHoverName().getString() + ": " + count);
    }
}
