package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.hud.HudHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The held item readout.
 *
 * Present in the September shipped jars ({@code HeldItemHud.class}) but absent
 * from this source tree; reconstructed from the bytecode, which shows the exact
 * behaviour: reads the main-hand stack, falls back to "Held: -" when empty, and
 * draws the display name with a " xN" suffix when more than one is carried.
 *
 * Deliberately thinner than Item Info (no durability, enchantment or registry
 * data): the shipped class only ever called getHoverName and getCount, so that
 * is all this restores.
 */
public class HeldItemHud extends AbstractHudModule {

    private final dev.breeze.settings.Setting.Bool icon =
            add(new dev.breeze.settings.Setting.Bool("icon", "Item icon", "Held Item", true));

    public HeldItemHud() {
        super("Held Item", Category.HUD, "Shows the item in your hand.", KEY_NONE, 4, 184);
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        if (mc.player == null) return;
        ItemStack held = mc.player.getMainHandItem();
        if (held.isEmpty()) {
            line(g, font, "Held: -");
            return;
        }
        if (icon.value) {
            // The item as Minecraft draws it in a slot: its count is on it.
            itemLine(g, font, held, held.getHoverName().getString(), HudHelper.WHITE);
            return;
        }
        String s = "Held: " + held.getHoverName().getString();
        if (held.getCount() > 1) s += " x" + held.getCount();
        line(g, font, s);
    }
}
