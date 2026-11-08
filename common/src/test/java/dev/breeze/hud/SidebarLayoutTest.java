package dev.breeze.hud;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SidebarLayoutTest {

    record E(String owner, int value) {}

    private static List<String> names(List<E> l) {
        List<String> out = new ArrayList<>();
        for (E e : l) out.add(e.owner());
        return out;
    }

    @Test
    void picksRowsAsMinecraftDoes() {
        List<E> in = List.of(new E("bob", 3), new E("#hidden", 99), new E("Alice", 3), new E("carl", 7), new E("dave", 1));
        assertEquals(List.of("carl", "Alice", "bob", "dave"), names(SidebarLayout.pick(in, E::value, E::owner)));
    }

    @Test
    void keepsTheFifteenHighest() {
        List<E> in = new ArrayList<>();
        for (int i = 0; i < 20; i++) in.add(new E("p" + i, i));
        List<E> got = SidebarLayout.pick(in, E::value, E::owner);
        assertEquals(SidebarLayout.MAX_ROWS, got.size());
        assertEquals("p19", got.get(0).owner());
        assertEquals("p5", got.get(14).owner());
    }

    @Test
    void laysOutTitleNamesAndNumbers() {
        SidebarLayout.Layout l = SidebarLayout.layout(40, new int[] {30, 50}, new int[] {6, 12}, 9, 1, 4, SidebarLayout.Align.CENTER, true);
        assertEquals(66, l.width(), "the widest name with its number: 50 + 4 + 12");
        assertEquals(13, l.titleX(), "centred: (66 - 40) / 2");
        assertArrayEquals(new int[] {11, 21}, l.rowY(), "title line, 2 apart, then rows 9 + 1 apart");
        assertEquals(30, l.height());
        assertEquals(66, l.numberRight());

        SidebarLayout.Layout none = SidebarLayout.layout(40, new int[] {30, 50}, new int[] {6, 12}, 9, 0, 4, SidebarLayout.Align.RIGHT, false);
        assertEquals(50, none.width(), "numbers left out");
        assertEquals(10, none.titleX());

        SidebarLayout.Layout empty = SidebarLayout.layout(40, new int[0], new int[0], 9, 0, 4, SidebarLayout.Align.LEFT, true);
        assertEquals(40, empty.width());
        assertEquals(9, empty.height());
    }
}
