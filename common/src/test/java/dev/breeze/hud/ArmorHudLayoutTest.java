package dev.breeze.hud;

import dev.breeze.hud.ArmorHudLayout.Align;
import dev.breeze.hud.ArmorHudLayout.Cell;
import dev.breeze.hud.ArmorHudLayout.Format;
import dev.breeze.hud.ArmorHudLayout.Options;
import dev.breeze.hud.ArmorHudLayout.Piece;
import dev.breeze.hud.ArmorHudLayout.Result;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.*;

class ArmorHudLayoutTest {

    /** Six pixels a character, close enough to Minecraft's font for layout. */
    private static final ToIntFunction<String> WIDTH = s -> s.length() * 6;
    private static final int WHITE = 0xFFFFFFFF;

    private static final Piece HELMET = new Piece(true, true, 363, 363, "Diamond Helmet");
    private static final Piece CHEST = new Piece(true, true, 264, 528, "Diamond Chestplate");
    private static final Piece LEGS = new Piece(true, true, 1, 495, "Iron Leggings");
    private static final Piece PUMPKIN = new Piece(true, false, 0, 0, "Carved Pumpkin");

    private static Options opts(Format f, boolean vertical, boolean icons) {
        return new Options(f, vertical, Align.LEFT, icons, false, false, true, 2);
    }

    @Test
    void percentNeverSaysFullOrEmptyWhenItIsNot() {
        assertEquals(100, ArmorHudLayout.percent(363, 363));
        assertEquals(99, ArmorHudLayout.percent(362, 363));
        assertEquals(50, ArmorHudLayout.percent(181, 362));
        assertEquals(1, ArmorHudLayout.percent(1, 495));
        assertEquals(0, ArmorHudLayout.percent(0, 495));
    }

    @Test
    void everyFormatSaysWhatItNames() {
        assertEquals("50%", ArmorHudLayout.text(CHEST, opts(Format.PERCENT, true, true)));
        assertEquals("264/528", ArmorHudLayout.text(CHEST, opts(Format.DURABILITY, true, true)));
        assertEquals("264", ArmorHudLayout.text(CHEST, opts(Format.REMAINING, true, true)));
        assertEquals("", ArmorHudLayout.text(CHEST, opts(Format.BAR, true, true)));
        assertEquals("50%", ArmorHudLayout.text(CHEST, opts(Format.BAR_AND_PERCENT, true, true)));
        Options named = new Options(Format.PERCENT, true, Align.LEFT, true, true, false, true, 2);
        assertEquals("Diamond Chestplate 50%", ArmorHudLayout.text(CHEST, named));
    }

    @Test
    void itemsWithoutDurabilityShowTheirIconOrTheirName() {
        assertEquals("", ArmorHudLayout.text(PUMPKIN, opts(Format.PERCENT, true, true)));
        assertEquals("Carved Pumpkin", ArmorHudLayout.text(PUMPKIN, opts(Format.PERCENT, true, false)));
    }

    @Test
    void durabilityColourMatchesMinecraftsBar() {
        assertEquals(0xFF00FF00, ArmorHudLayout.durabilityColor(10, 10));
        assertEquals(0xFFFFFF00, ArmorHudLayout.durabilityColor(5, 10));
        assertEquals(0xFFFF0000, ArmorHudLayout.durabilityColor(0, 10));
    }

    @Test
    void verticalStacksIconRowsWithTheSpacing() {
        Result r = ArmorHudLayout.layout(List.of(HELMET, CHEST), opts(Format.PERCENT, true, true), WIDTH, WHITE);
        assertEquals(2, r.cells().size());
        Cell a = r.cells().get(0), b = r.cells().get(1);
        assertEquals(0, a.y());
        assertEquals(ArmorHudLayout.ICON + 2, b.y());
        assertEquals(ArmorHudLayout.ICON * 2 + 2, r.height());
        // icon, gap, then "100%"
        assertEquals(ArmorHudLayout.ICON + ArmorHudLayout.INNER_GAP, a.textX());
        assertEquals(ArmorHudLayout.ICON + ArmorHudLayout.INNER_GAP + 4 * 6, r.width());
        // Text sits in the middle of the icon's height.
        assertEquals((ArmorHudLayout.ICON - ArmorHudLayout.TEXT_H) / 2, a.textY());
        assertTrue(a.icon());
        assertEquals(0xFF00FF00, a.textColor());
    }

    @Test
    void horizontalPutsPiecesSideBySideWithAtLeastFourPixels() {
        Options o = new Options(Format.PERCENT, false, Align.LEFT, true, false, false, true, 0);
        Result r = ArmorHudLayout.layout(List.of(HELMET, CHEST), o, WIDTH, WHITE);
        Cell a = r.cells().get(0), b = r.cells().get(1);
        int aw = ArmorHudLayout.ICON + ArmorHudLayout.INNER_GAP + 4 * 6;
        assertEquals(0, a.x());
        assertEquals(aw + ArmorHudLayout.MIN_ACROSS_GAP, b.x());
        assertEquals(ArmorHudLayout.ICON, r.height());
        assertEquals(0, b.y());
    }

    @Test
    void barFormatDrawsABarAndNoText() {
        Result r = ArmorHudLayout.layout(List.of(CHEST), opts(Format.BAR, true, true), WIDTH, WHITE);
        Cell c = r.cells().get(0);
        assertTrue(c.bar());
        assertEquals("", c.text());
        assertEquals(ArmorHudLayout.ICON + ArmorHudLayout.INNER_GAP, c.barX());
        assertEquals(ArmorHudLayout.BAR_W / 2, c.barFill());
        assertEquals(ArmorHudLayout.ICON + ArmorHudLayout.INNER_GAP + ArmorHudLayout.BAR_W, r.width());
    }

    @Test
    void barAndPercentPutsTheTextAfterTheBar() {
        Result r = ArmorHudLayout.layout(List.of(LEGS), opts(Format.BAR_AND_PERCENT, true, false), WIDTH, WHITE);
        Cell c = r.cells().get(0);
        assertEquals(0, c.barX());
        assertEquals(1, c.barFill()); // one use left still shows
        assertEquals(ArmorHudLayout.BAR_W + ArmorHudLayout.INNER_GAP, c.textX());
        assertEquals("1%", c.text());
    }

    @Test
    void emptySlotsAreLeftOutUnlessKept() {
        List<Piece> pieces = List.of(Piece.EMPTY, CHEST, Piece.EMPTY, Piece.EMPTY);
        Result skipped = ArmorHudLayout.layout(pieces, opts(Format.PERCENT, true, true), WIDTH, WHITE);
        assertEquals(1, skipped.cells().size());
        assertEquals(1, skipped.cells().get(0).slot());

        Options keep = new Options(Format.PERCENT, true, Align.LEFT, false, false, true, true, 2);
        Result kept = ArmorHudLayout.layout(pieces, keep, WIDTH, WHITE);
        assertEquals(4, kept.cells().size());
        assertEquals("-", kept.cells().get(0).text());
        assertEquals(WHITE, kept.cells().get(0).textColor());
    }

    @Test
    void noArmourMeansNothingToDraw() {
        Result r = ArmorHudLayout.layout(List.of(Piece.EMPTY, Piece.EMPTY), opts(Format.PERCENT, true, true), WIDTH, WHITE);
        assertTrue(r.cells().isEmpty());
        assertEquals(0, r.width());
        assertEquals(0, r.height());
    }

    @Test
    void rightAlignmentLinesUpTheRightEdges() {
        Options o = new Options(Format.DURABILITY, true, Align.RIGHT, false, false, false, true, 2);
        Result r = ArmorHudLayout.layout(List.of(HELMET, LEGS), o, WIDTH, WHITE);
        Cell a = r.cells().get(0), b = r.cells().get(1);
        assertEquals(r.width(), a.x() + 7 * 6); // "363/363"
        assertEquals(r.width(), b.x() + 5 * 6); // "1/495"
    }

    @Test
    void colourCanBeTurnedOff() {
        Options o = new Options(Format.PERCENT, true, Align.LEFT, true, false, false, false, 2);
        Result r = ArmorHudLayout.layout(List.of(LEGS), o, WIDTH, WHITE);
        assertEquals(WHITE, r.cells().get(0).textColor());
    }

    @Test
    void formatLabelsRoundTrip() {
        for (Format f : Format.values()) assertEquals(f, Format.of(f.label));
        assertEquals(Format.PERCENT, Format.of("nonsense"));
    }

    @Test
    void handsShowTheSwordsDurabilityAndHowManyOfAStack() {
        Piece sword = new Piece(true, true, 780, 1561, "Diamond Sword");
        Piece shield = new Piece(true, true, 300, 336, "Shield");
        Piece totems = new Piece(true, false, 0, 0, "Totem of Undying", 3);
        Piece oneTotem = new Piece(true, false, 0, 0, "Totem of Undying", 1);
        Options o = opts(Format.PERCENT, true, true);
        assertEquals("50%", ArmorHudLayout.text(sword, o));
        assertEquals("89%", ArmorHudLayout.text(shield, o));
        assertEquals("3", ArmorHudLayout.text(totems, o));
        assertEquals("", ArmorHudLayout.text(oneTotem, o));
        Options named = new Options(Format.PERCENT, true, Align.LEFT, true, true, false, true, 2);
        assertEquals("Totem of Undying 3", ArmorHudLayout.text(totems, named));

        Result r = ArmorHudLayout.layout(List.of(HELMET, CHEST, LEGS, Piece.EMPTY, sword, shield), o, WIDTH, WHITE);
        assertEquals(5, r.cells().size());
        Cell swordCell = r.cells().get(3);
        assertEquals(4, swordCell.slot());
        assertTrue(swordCell.icon());
        assertEquals(ArmorHudLayout.durabilityColor(780, 1561), swordCell.textColor());
    }
}
