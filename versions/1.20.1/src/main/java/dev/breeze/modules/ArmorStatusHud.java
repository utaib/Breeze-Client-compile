package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.hud.ArmorHudLayout;
import dev.breeze.hud.ArmorHudLayout.Cell;
import dev.breeze.hud.ArmorHudLayout.Piece;
import dev.breeze.settings.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The Armor HUD: each armour piece you wear, with its item icon and what is
 * left of it as a percentage, a number or a bar, stacked or in a row.
 *
 * Layout, formats and colours are {@link ArmorHudLayout} (tested in common);
 * this class reads the real armour and draws what the layout says. The shared
 * HUD style supplies size, background, border, colours and spacing, and the HUD
 * editor its position.
 *
 * History: this module once drew the held item under this name. It then became
 * a text list ("Head: Diamond Helmet 87%"). The spec asks for number, bar and
 * percentage formats, orientation and the usual appearance controls.
 */
public class ArmorStatusHud extends AbstractHudModule {

    private static final String GROUP = "Armor";
    private static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private final Setting.Mode format =
            add(new Setting.Mode("format", "Show", GROUP, ArmorHudLayout.Format.labels(), 0));
    private final Setting.Mode orientation =
            add(new Setting.Mode("orientation", "Orientation", GROUP, new String[]{"Vertical", "Horizontal"}, 0));
    private final Setting.Bool icons = add(new Setting.Bool("icons", "Item icons", GROUP, true));
    private final Setting.Bool names = add(new Setting.Bool("names", "Item names", GROUP, false));
    private final Setting.Bool colours = add(new Setting.Bool("colours", "Colour by durability", GROUP, true));
    private final Setting.Bool empties = add(new Setting.Bool("empties", "Keep empty slots", GROUP, false));

    /** Reused every frame; the HUD draws sixty times a second. */
    private final List<Piece> pieces = new ArrayList<>(SLOTS.length);
    private final ItemStack[] stacks = new ItemStack[SLOTS.length];

    public ArmorStatusHud() {
        super("Armor Status", Category.HUD, "Shows each armour piece with its durability as a percentage, a number or a bar.", KEY_NONE, 4, 264);
    }

    @Override
    protected boolean drawsShapes() {
        return true;
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        if (mc.player == null) return;
        pieces.clear();
        for (int i = 0; i < SLOTS.length; i++) {
            ItemStack st = mc.player.getItemBySlot(SLOTS[i]);
            stacks[i] = st;
            if (st.isEmpty()) {
                pieces.add(Piece.EMPTY);
            } else if (st.isDamageableItem()) {
                int max = st.getMaxDamage();
                pieces.add(new Piece(true, true, max - st.getDamageValue(), max, st.getHoverName().getString()));
            } else {
                pieces.add(new Piece(true, false, 0, 0, st.getHoverName().getString()));
            }
        }

        ArmorHudLayout.Options options = new ArmorHudLayout.Options(
                ArmorHudLayout.Format.of(format.value()),
                orientation.is("Vertical"),
                ArmorHudLayout.Align.of(style().align.value()),
                icons.value, names.value, empties.value, colours.value,
                style().lineGap.value);
        ArmorHudLayout.Result layout = ArmorHudLayout.layout(pieces, options,
                s -> font.width(style().applyCase(s)), style().textColor.argb);

        for (Cell c : layout.cells()) {
            if (c.icon()) g.renderItem(stacks[c.slot()], x + c.x(), y + c.y());
            if (c.bar()) {
                int bx = x + c.barX(), by = y + c.barY();
                g.fill(bx, by, bx + ArmorHudLayout.BAR_W, by + ArmorHudLayout.BAR_H, 0xFF000000);
                g.fill(bx, by, bx + c.barFill(), by + ArmorHudLayout.BAR_H, c.barColor());
            }
            if (!c.text().isEmpty()) {
                g.drawString(font, style().applyCase(c.text()), x + c.textX(), y + c.textY(),
                        c.textColor(), style().textShadow.value);
            }
        }
        bounds(layout.width(), layout.height());
    }
}
