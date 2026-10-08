package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.hud.InventoryHudLayout;
import dev.breeze.settings.Setting;
import dev.breeze.ui.GameTextures;
import dev.breeze.ui.ModuleIconCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Your real inventory on the HUD, read from the player every frame and drawn
 * by Minecraft's own item renderer (counts, durability bars and enchantment
 * glint included), on Minecraft's own slot picture, in the order its
 * inventory screen uses ({@link InventoryHudLayout}). Optionally the armour
 * you wear and your off hand, with Minecraft's empty-slot pictures where a
 * slot is empty. Nothing is copied or kept between frames.
 */
public class InventoryHud extends AbstractHudModule {

    /** The player inventory's main slots, hotbar included: 0 to 35 in every version. */
    static final int MAIN_SLOTS = InventoryHudLayout.MAIN;
    private static final String GROUP = "Inventory";
    private static final EquipmentSlot[] ARMOUR = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };
    private static final String[] EMPTY_ARMOUR = {
            GameTextures.EMPTY_HELMET, GameTextures.EMPTY_CHESTPLATE, GameTextures.EMPTY_LEGGINGS, GameTextures.EMPTY_BOOTS
    };
    private static final boolean RECORD = System.getProperty("breeze.autotest") != null;

    private final Setting.Bool hotbar = add(new Setting.Bool("hotbar", "Hotbar row", GROUP, true));
    private final Setting.Bool armour = add(new Setting.Bool("armour", "Armour and off hand", GROUP, false));
    private final Setting.Bool slots = add(new Setting.Bool("slots", "Slot backgrounds", GROUP, true));
    /** What the last frame drew, for the self-test: "slot item count" per filled slot. */
    private final List<String> lastDrawn = new ArrayList<>(41);

    public InventoryHud() {
        super("Inventory HUD", Category.HUD, "Shows your real inventory on the HUD, laid out as Minecraft's inventory screen.", KEY_NONE, 4, 4);
    }

    /** Top right until moved, where it always used to be. */
    @Override
    protected dev.breeze.hud.HudPlacement defaultPlacement() {
        return new dev.breeze.hud.HudPlacement(dev.breeze.hud.HudPlacement.H.RIGHT, dev.breeze.hud.HudPlacement.V.TOP, 4, 4);
    }

    @Override
    protected boolean drawsShapes() {
        return true;
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        InventoryHudLayout.Result layout = InventoryHudLayout.layout(new InventoryHudLayout.Options(hotbar.value, armour.value));
        // The size is known without a player, so the HUD editor can place the
        // grid before there is anything in it.
        bounds(layout.width(), layout.height());
        if (mc.player == null) return;
        if (RECORD) lastDrawn.clear();
        Inventory inv = mc.player.getInventory();
        for (InventoryHudLayout.Cell c : layout.cells()) {
            int sx = x + c.x(), sy = y + c.y();
            if (slots.value) {
                ModuleIconCache.drawTexture(g, GameTextures.SLOT, GameTextures.sources(GameTextures.SLOT),
                        sx, sy, InventoryHudLayout.SLOT);
            }
            // By index: the list behind the main slots stopped being public in
            // Minecraft 1.21.5, getItem did not.
            ItemStack st = switch (c.kind()) {
                case INVENTORY -> inv.getItem(c.index());
                case ARMOR -> mc.player.getItemBySlot(ARMOUR[c.index()]);
                case OFFHAND -> mc.player.getOffhandItem();
            };
            if (st.isEmpty()) {
                String empty = c.kind() == InventoryHudLayout.Kind.ARMOR ? EMPTY_ARMOUR[c.index()]
                        : c.kind() == InventoryHudLayout.Kind.OFFHAND ? GameTextures.EMPTY_SHIELD : null;
                if (empty != null) ModuleIconCache.drawTexture(g, empty, GameTextures.sources(empty), sx + 1, sy + 1, 16);
                continue;
            }
            g.renderItem(st, sx + 1, sy + 1);
            g.renderItemDecorations(mc.font, st, sx + 1, sy + 1);
            if (RECORD) {
                lastDrawn.add(c.kind().name().toLowerCase(java.util.Locale.ROOT) + c.index() + " "
                        + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()) + " " + st.getCount());
            }
        }
    }

    /** For the self-test: each filled slot the last frame drew, as "inventory12 minecraft:stone 64". */
    public List<String> lastDrawn() {
        return List.copyOf(lastDrawn);
    }
}
