package dev.breeze.hud;

import dev.breeze.hud.InventoryHudLayout.Cell;
import dev.breeze.hud.InventoryHudLayout.Kind;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class InventoryHudLayoutTest {

    private static Cell find(List<Cell> cells, Kind kind, int index) {
        return cells.stream().filter(c -> c.kind() == kind && c.index() == index).findFirst().orElseThrow();
    }

    @Test
    void mainRowsThenTheHotbarBelowLikeMinecraftsInventory() {
        InventoryHudLayout.Result r = InventoryHudLayout.layout(new InventoryHudLayout.Options(true, false));
        assertEquals(36, r.cells().size());
        assertEquals(new Cell(Kind.INVENTORY, 9, 0, 0), find(r.cells(), Kind.INVENTORY, 9));
        assertEquals(new Cell(Kind.INVENTORY, 35, 8 * 18, 2 * 18), find(r.cells(), Kind.INVENTORY, 35));
        // The hotbar is the bottom row, 4 pixels below the main grid.
        assertEquals(new Cell(Kind.INVENTORY, 0, 0, 3 * 18 + 4), find(r.cells(), Kind.INVENTORY, 0));
        assertEquals(new Cell(Kind.INVENTORY, 8, 8 * 18, 3 * 18 + 4), find(r.cells(), Kind.INVENTORY, 8));
        assertEquals(9 * 18, r.width());
        assertEquals(4 * 18 + 4, r.height());
    }

    @Test
    void everyInventorySlotOnceAndNoTwoInOnePlace() {
        InventoryHudLayout.Result r = InventoryHudLayout.layout(new InventoryHudLayout.Options(true, true));
        Set<Integer> slots = new HashSet<>();
        Set<String> places = new HashSet<>();
        for (Cell c : r.cells()) {
            if (c.kind() == Kind.INVENTORY) assertTrue(slots.add(c.index()));
            assertTrue(places.add(c.x() + "," + c.y()), "two slots at " + c);
            assertTrue(c.x() >= 0 && c.y() >= 0 && c.x() + 18 <= r.width() && c.y() + 18 <= r.height(), c.toString());
        }
        assertEquals(36, slots.size());
        assertEquals(41, r.cells().size());
    }

    @Test
    void withoutTheHotbarOnlyTheMainGrid() {
        InventoryHudLayout.Result r = InventoryHudLayout.layout(new InventoryHudLayout.Options(false, false));
        assertEquals(27, r.cells().size());
        assertTrue(r.cells().stream().allMatch(c -> c.index() >= 9));
        assertEquals(3 * 18, r.height());
    }

    @Test
    void armourOnTheLeftHelmetFirstAndTheOffHandAfterTheHotbar() {
        InventoryHudLayout.Result r = InventoryHudLayout.layout(new InventoryHudLayout.Options(true, true));
        assertEquals(new Cell(Kind.ARMOR, 0, 0, 0), find(r.cells(), Kind.ARMOR, 0));
        assertEquals(new Cell(Kind.ARMOR, 3, 0, 54), find(r.cells(), Kind.ARMOR, 3));
        // The grid moves right of the armour column.
        assertEquals(22, find(r.cells(), Kind.INVENTORY, 9).x());
        Cell off = find(r.cells(), Kind.OFFHAND, 0);
        assertEquals(22 + 9 * 18 + 4, off.x());
        assertEquals(find(r.cells(), Kind.INVENTORY, 0).y(), off.y());
        assertEquals(off.x() + 18, r.width());
    }

    @Test
    void armourWithoutTheHotbarStillFitsTheColumn() {
        InventoryHudLayout.Result r = InventoryHudLayout.layout(new InventoryHudLayout.Options(false, true));
        assertEquals(4 * 18, r.height());
        assertEquals(36, find(r.cells(), Kind.OFFHAND, 0).y());
    }
}
