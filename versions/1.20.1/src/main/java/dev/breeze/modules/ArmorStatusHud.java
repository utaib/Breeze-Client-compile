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
 * The Armor HUD: each armour piece you wear, and what is in each hand (a sword,
 * a shield, a stack of totems), with its item icon and what is left of it as a
 * percentage, a number or a bar, stacked or in a row. A stack shows its count.
 *
 * Layout, formats and colours are {@link ArmorHudLayout} (tested in common);
 * this class reads the real armour and draws what the layout says. The shared
 * HUD style supplies size, background, border, colours and spacing, and the HUD
 * editor its position.
 *
 * History: this module once drew the held item under this name. It then became
 * a text list ("Head: Diamond Helmet 87%"). The spec asks for number, bar and
 * percentage formats, orientation and the usual appearance controls. 2.14.0
 * adds the Hotbar look, asked for by a creator: the pieces in slots cut from
 * Minecraft's own hotbar ({@link dev.breeze.ui.HotbarFrames}), so the armour
 * reads as a second hotbar beside the real one, with Minecraft's own
 * durability bar on each item or the number in the chosen format.
 */
public class ArmorStatusHud extends AbstractHudModule {

    private static final String GROUP = "Armor";
    private static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND
    };
    /** Minecraft's empty-slot picture for each of SLOTS ({@link dev.breeze.ui.GameTextures}). */
    private static final String[] EMPTY_PICTURES = {
            dev.breeze.ui.GameTextures.EMPTY_HELMET, dev.breeze.ui.GameTextures.EMPTY_CHESTPLATE,
            dev.breeze.ui.GameTextures.EMPTY_LEGGINGS, dev.breeze.ui.GameTextures.EMPTY_BOOTS,
            null, dev.breeze.ui.GameTextures.EMPTY_SHIELD
    };
    /** SLOTS from here on are the hands. */
    private static final int FIRST_HAND = 4;

    private final Setting.Mode look =
            add(new Setting.Mode("look", "Look", GROUP, new String[]{"Breeze", "Hotbar"}, 0));
    private final Setting.Mode format =
            add(new Setting.Mode("format", "Show", GROUP, ArmorHudLayout.Format.labels(), 0));
    private final Setting.Mode orientation =
            add(new Setting.Mode("orientation", "Orientation", GROUP, new String[]{"Vertical", "Horizontal"}, 0));
    private final Setting.Bool icons = add(new Setting.Bool("icons", "Item icons", GROUP, true));
    private final Setting.Bool names = add(new Setting.Bool("names", "Item names", GROUP, false));
    private final Setting.Bool colours = add(new Setting.Bool("colours", "Colour by durability", GROUP, true));
    private final Setting.Bool empties = add(new Setting.Bool("empties", "Keep empty slots", GROUP, false));
    private final Setting.Bool hands = add(new Setting.Bool("hands", "Hands", GROUP, true));

    /** Reused every frame; the HUD draws sixty times a second. */
    private final List<Piece> pieces = new ArrayList<>(SLOTS.length);
    private final ItemStack[] stacks = new ItemStack[SLOTS.length];
    /** Only the self-test reads what was drawn; players' frames build no strings for it. */
    private static final boolean RECORD = System.getProperty("breeze.autotest") != null;
    /** What the last frame drew, for the self-test: one entry per piece shown. */
    private final List<String> lastDrawn = new ArrayList<>(SLOTS.length);
    /** For the self-test: how many strips of hotbar slots the last frame drew. */
    private int lastFrames;

    public ArmorStatusHud() {
        super("Armor Status", Category.HUD, "Shows your armour and what is in your hands, with durability as a number or a bar, in Breeze's look or in Minecraft's hotbar slots.", KEY_NONE, 4, 264);
    }

    @Override
    protected boolean drawsShapes() {
        return true;
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        if (mc.player == null) return;
        pieces.clear();
        int slots = hands.value ? SLOTS.length : FIRST_HAND;
        for (int i = 0; i < slots; i++) {
            ItemStack st = mc.player.getItemBySlot(SLOTS[i]);
            stacks[i] = st;
            if (st.isEmpty()) {
                pieces.add(Piece.EMPTY);
            } else if (st.isDamageableItem()) {
                int max = st.getMaxDamage();
                pieces.add(new Piece(true, true, max - st.getDamageValue(), max, st.getHoverName().getString()));
            } else {
                pieces.add(new Piece(true, false, 0, 0, st.getHoverName().getString(), st.getCount()));
            }
        }

        ArmorHudLayout.Options options = new ArmorHudLayout.Options(
                ArmorHudLayout.Format.of(format.value()),
                orientation.is("Vertical"),
                ArmorHudLayout.Align.of(style().align.value()),
                icons.value, names.value, empties.value, colours.value,
                style().lineGap.value, look.is("Hotbar"));
        ArmorHudLayout.Result layout = ArmorHudLayout.layout(pieces, options,
                s -> font.width(style().applyCase(s)), style().textColor.argb);

        if (RECORD) lastDrawn.clear();
        for (ArmorHudLayout.Frame f : layout.frames()) {
            dev.breeze.ui.HotbarFrames.draw(g, x + f.x(), y + f.y(), f.slots(), f.vertical());
        }
        if (RECORD) lastFrames = layout.frames().size();
        for (Cell c : layout.cells()) {
            if (RECORD) {
                lastDrawn.add(SLOTS[c.slot()].name().toLowerCase(java.util.Locale.ROOT) + " " + c.text()
                        + " #" + Integer.toHexString(c.textColor()));
            }
            if (c.icon()) {
                ItemStack st = stacks[c.slot()];
                if (!st.isEmpty()) {
                    g.renderItem(st, x + c.x(), y + c.y());
                    // Hotbar look: the durability bar and count exactly as
                    // the hotbar draws them.
                    if (c.decorations()) g.renderItemDecorations(font, st, x + c.x(), y + c.y());
                } else {
                    // An empty slot shows Minecraft's own empty-slot picture,
                    // as its inventory does (the main hand has none).
                    String empty = EMPTY_PICTURES[c.slot()];
                    if (empty != null) {
                        dev.breeze.ui.ModuleIconCache.drawTexture(g, empty, dev.breeze.ui.GameTextures.sources(empty),
                                x + c.x(), y + c.y(), 16);
                    }
                }
            }
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

    /** For the self-test: each piece the last frame drew, as "slot text #colour". */
    public List<String> lastDrawn() {
        return List.copyOf(lastDrawn);
    }

    /** For the self-test: the strips of hotbar slots the last frame drew. */
    public int lastFrames() {
        return lastFrames;
    }

    /** For the self-test: switch between the Breeze and Hotbar looks. */
    public void hotbarLook(boolean on) {
        look.index = on ? 1 : 0;
    }
}
