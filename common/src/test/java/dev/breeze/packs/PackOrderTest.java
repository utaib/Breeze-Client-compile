package dev.breeze.packs;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PackOrderTest {

    private static final List<PackOrder.Entry> AVAILABLE = List.of(
            new PackOrder.Entry("vanilla", true, true, false, true),
            new PackOrder.Entry("fabric", true, true, false, true),
            new PackOrder.Entry("programmer_art", false, false, true, false),
            new PackOrder.Entry("file/Faithful.zip", false, false, true, false),
            new PackOrder.Entry("file/Dark UI", false, false, true, false),
            new PackOrder.Entry("server", false, true, true, true),
            new PackOrder.Entry("modpack-extra", false, true, false, false));

    @Test
    void topFirstBecomesBottomFirstWithRequiredPacksKept() {
        List<String> got = PackOrder.selection(AVAILABLE, List.of("file/Dark UI", "file/Faithful.zip"));
        assertEquals(List.of("vanilla", "fabric", "file/Faithful.zip", "file/Dark UI", "server"), got,
                "the game's own packs stay at the bottom, a fixed top pack that is on stays on top; the first asked for ends top of the rest");
    }

    @Test
    void requiredPacksCannotBeTurnedOffOrMovedAndUnknownNamesAreLeftOut() {
        List<String> got = PackOrder.selection(AVAILABLE,
                List.of("vanilla", "file/Dark UI", "file/missing.zip", "file/Dark UI", "programmer_art"));
        assertEquals(List.of("vanilla", "fabric", "programmer_art", "file/Dark UI", "server"), got);
        assertEquals(List.of("vanilla", "fabric", "server"), PackOrder.selection(AVAILABLE, List.of()));
    }

    @Test
    void fixedPacksStayAsTheyAreWhateverIsAsked() {
        // Asking for a fixed pack that is off, or leaving out one that is on, changes nothing.
        List<String> got = PackOrder.selection(AVAILABLE, List.of("modpack-extra", "file/Faithful.zip"));
        assertEquals(List.of("vanilla", "fabric", "file/Faithful.zip", "server"), got);
        assertTrue(AVAILABLE.get(5).locked() && AVAILABLE.get(0).locked() && !AVAILABLE.get(2).locked());
    }

    @Test
    void plainTextDropsFormattingCodes() {
        assertEquals("Faithful 32x\nFor 1.21", PackOrder.plain("§6Faithful §l32x\n§7For 1.21\u0007", 100));
        assertEquals("abc", PackOrder.plain("abcdef", 3));
        assertEquals("", PackOrder.plain(null, 10));
    }
}
