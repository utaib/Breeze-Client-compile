package dev.breeze.hud;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the Inventory HUD puts each of the player's real slots, in the order
 * Minecraft's own inventory screen shows them: the 27 main slots in three
 * rows, then the hotbar below them after a 4 pixel gap; optionally the four
 * armour slots in a column on the left (helmet on top) and the off hand after
 * the hotbar. Every slot is 18 by 18, the item drawn 1 pixel in, as in a
 * container. The game side reads each slot from the player's inventory and
 * draws it here; nothing about the contents is decided in this class.
 */
public final class InventoryHudLayout {

    public static final int SLOT = 18;
    public static final int GAP = 4;
    public static final int COLS = 9;
    /** Hotbar slots are 0 to 8 of the inventory, the main grid 9 to 35. */
    public static final int HOTBAR = 9;
    public static final int MAIN = 36;

    public enum Kind { INVENTORY, ARMOR, OFFHAND }

    /**
     * One slot: which (an inventory index, or an armour piece 0 = helmet to
     * 3 = boots) and its top-left corner relative to the element.
     */
    public record Cell(Kind kind, int index, int x, int y) {}

    public record Options(boolean hotbar, boolean armour) {}

    public record Result(List<Cell> cells, int width, int height) {}

    private InventoryHudLayout() {}

    public static Result layout(Options o) {
        List<Cell> cells = new ArrayList<>(41);
        int left = o.armour() ? SLOT + GAP : 0;
        for (int i = HOTBAR; i < MAIN; i++) {
            int r = (i - HOTBAR) / COLS, c = (i - HOTBAR) % COLS;
            cells.add(new Cell(Kind.INVENTORY, i, left + c * SLOT, r * SLOT));
        }
        int gridH = 3 * SLOT;
        int hotbarY = gridH + GAP;
        if (o.hotbar()) {
            for (int i = 0; i < HOTBAR; i++) cells.add(new Cell(Kind.INVENTORY, i, left + i * SLOT, hotbarY));
            gridH = hotbarY + SLOT;
        }
        int width = left + COLS * SLOT;
        int height = gridH;
        if (o.armour()) {
            for (int a = 0; a < 4; a++) cells.add(new Cell(Kind.ARMOR, a, 0, a * SLOT));
            height = Math.max(height, 4 * SLOT);
            // The off hand sits after the hotbar, as Minecraft's HUD draws it
            // beside its hotbar; without the hotbar row, after the last row.
            int offY = o.hotbar() ? hotbarY : 2 * SLOT;
            cells.add(new Cell(Kind.OFFHAND, 0, width + GAP, offY));
            width += GAP + SLOT;
        }
        return new Result(List.copyOf(cells), width, height);
    }
}
