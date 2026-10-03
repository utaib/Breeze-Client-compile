package dev.breeze.hud;

import dev.breeze.hud.HudPlacement.Box;
import dev.breeze.hud.HudPlacement.H;
import dev.breeze.hud.HudPlacement.Snap;
import dev.breeze.hud.HudPlacement.V;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HudPlacementTest {

    @Test
    void anchorsToTheThirdTheCentreIsIn() {
        HudPlacement tl = HudPlacement.of(4, 4, 40, 10, 480, 270);
        assertEquals(H.LEFT, tl.h);
        assertEquals(V.TOP, tl.v);
        assertEquals(4, tl.dx);
        assertEquals(4, tl.dy);

        HudPlacement br = HudPlacement.of(430, 250, 46, 16, 480, 270);
        assertEquals(H.RIGHT, br.h);
        assertEquals(V.BOTTOM, br.v);
        assertEquals(4, br.dx, "distance from the right edge");
        assertEquals(4, br.dy, "distance from the bottom edge");

        HudPlacement mid = HudPlacement.of(220, 130, 40, 10, 480, 270);
        assertEquals(H.CENTER, mid.h);
        assertEquals(V.MIDDLE, mid.v);
        assertEquals(0, mid.dx);
        assertEquals(0, mid.dy);
    }

    @Test
    void resolvesBackToWhereItWasOnTheSameScreen() {
        int[][] spots = {{4, 4}, {430, 250}, {220, 130}, {100, 200}, {300, 20}};
        for (int[] s : spots) {
            HudPlacement p = HudPlacement.of(s[0], s[1], 46, 16, 480, 270);
            assertArrayEquals(s, p.resolve(46, 16, 480, 270), p.toString());
        }
    }

    @Test
    void rightAnchoredElementFollowsTheRightEdgeWhenTheScreenGrows() {
        HudPlacement p = HudPlacement.of(430, 4, 46, 16, 480, 270);
        assertArrayEquals(new int[]{854 - 46 - 4, 4}, p.resolve(46, 16, 854, 480));
    }

    @Test
    void centredElementStaysCentred() {
        HudPlacement p = HudPlacement.of(220, 130, 40, 10, 480, 270);
        int[] big = p.resolve(40, 10, 960, 540);
        assertEquals(960 / 2 - 20, big[0]);
        assertEquals(540 / 2 - 5, big[1]);
    }

    @Test
    void neverLeavesTheScreen() {
        // An old absolute position from a large window, on a small one.
        HudPlacement p = HudPlacement.topLeft(900, 700);
        assertArrayEquals(new int[]{480 - 46, 270 - 16}, p.resolve(46, 16, 480, 270));
        assertArrayEquals(new int[]{0, 0}, HudPlacement.topLeft(-20, -5).resolve(46, 16, 480, 270));
        // Larger than the screen: the top-left corner stays visible.
        assertArrayEquals(new int[]{0, 0}, HudPlacement.topLeft(10, 10).resolve(600, 300, 480, 270));
    }

    @Test
    void snapsToScreenEdgesAndCentre() {
        Snap left = HudPlacement.snap(3, 50, 40, 10, 480, 270, List.of(), 4);
        assertEquals(0, left.x());
        assertEquals(List.of(0), left.guidesX());

        Snap centre = HudPlacement.snap(218, 50, 40, 10, 480, 270, List.of(), 4);
        assertEquals(220, centre.x(), "centre line of the element on the screen's");
        assertEquals(List.of(240), centre.guidesX());

        Snap bottom = HudPlacement.snap(50, 258, 40, 10, 480, 270, List.of(), 4);
        assertEquals(260, bottom.y());
    }

    @Test
    void snapsToAnotherElementAndLeavesFarOnesAlone() {
        List<Box> others = List.of(new Box(100, 100, 50, 12));
        Snap under = HudPlacement.snap(102, 114, 40, 10, 480, 270, others, 4);
        assertEquals(100, under.x(), "left edges line up");
        assertEquals(112, under.y(), "top touches the other's bottom");

        Snap free = HudPlacement.snap(300, 40, 40, 10, 480, 270, others, 4);
        assertEquals(300, free.x());
        assertEquals(40, free.y());
        assertTrue(free.guidesX().isEmpty());
        assertTrue(free.guidesY().isEmpty());
    }

    @Test
    void snappingNeverPushesOffScreen() {
        Snap s = HudPlacement.snap(478, 268, 40, 10, 480, 270, List.of(), 4);
        assertTrue(s.x() + 40 <= 480);
        assertTrue(s.y() + 10 <= 270);
    }

    @Test
    void stackDoesNotOverlapAndWrapsIntoColumns() {
        List<int[]> sizes = List.of(new int[]{40, 10}, new int[]{60, 20}, new int[]{30, 220}, new int[]{50, 10});
        List<int[]> at = HudPlacement.stack(sizes, 270, 4, 2);
        assertArrayEquals(new int[]{4, 4}, at.get(0));
        assertArrayEquals(new int[]{4, 16}, at.get(1));
        assertArrayEquals(new int[]{4, 38}, at.get(2));
        // The fourth would run past the bottom: next column, right of the widest.
        assertArrayEquals(new int[]{4 + 60 + 2, 4}, at.get(3));
        for (int i = 0; i < sizes.size(); i++) {
            for (int j = i + 1; j < sizes.size(); j++) {
                assertFalse(overlap(at.get(i), sizes.get(i), at.get(j), sizes.get(j)), i + " and " + j);
            }
        }
    }

    @Test
    void freeSpotKeepsTheDefaultWhenItIsFree() {
        List<HudPlacement.Box> taken = List.of(new HudPlacement.Box(4, 4, 30, 10));
        assertArrayEquals(new int[]{4, 24}, HudPlacement.freeSpot(4, 24, 100, 10, taken, 427, 240, 4, 2));
    }

    @Test
    void freeSpotMovesAnElementOffOneItWouldCover() {
        List<HudPlacement.Box> taken = List.of(
                new HudPlacement.Box(4, 4, 30, 10),
                new HudPlacement.Box(4, 16, 100, 10));
        int[] at = HudPlacement.freeSpot(4, 4, 60, 10, taken, 427, 240, 4, 2);
        assertNotNull(at);
        for (HudPlacement.Box b : taken) {
            assertFalse(overlap(at, new int[]{60, 10}, new int[]{b.x(), b.y()}, new int[]{b.w(), b.h()}), "clear of " + b);
        }
        assertEquals(4, at[0], "first down the left edge");
        assertEquals(28, at[1], "just under the second, with the gap");
    }

    @Test
    void freeSpotGoesToTheNextColumnAndGivesUpWhenFull() {
        // The left edge is full from top to bottom.
        List<HudPlacement.Box> column = List.of(new HudPlacement.Box(0, 0, 50, 240));
        int[] at = HudPlacement.freeSpot(4, 4, 40, 10, column, 427, 240, 4, 2);
        assertNotNull(at);
        assertTrue(at[0] >= 52, "right of the full column: " + at[0]);
        List<HudPlacement.Box> everything = List.of(new HudPlacement.Box(0, 0, 427, 240));
        assertNull(HudPlacement.freeSpot(4, 4, 40, 10, everything, 427, 240, 4, 2));
    }

    @Test
    void encodesAndDecodes() {
        HudPlacement p = new HudPlacement(H.RIGHT, V.BOTTOM, 4, -2);
        assertEquals("RIGHT,BOTTOM,4,-2", p.encode());
        assertEquals(p, HudPlacement.decode(p.encode()));
        assertNull(HudPlacement.decode("nonsense"));
        assertNull(HudPlacement.decode("LEFT,TOP,x,1"));
        assertNull(HudPlacement.decode(null));
    }

    private static boolean overlap(int[] a, int[] as, int[] b, int[] bs) {
        return a[0] < b[0] + bs[0] && b[0] < a[0] + as[0] && a[1] < b[1] + bs[1] && b[1] < a[1] + as[1];
    }
}
