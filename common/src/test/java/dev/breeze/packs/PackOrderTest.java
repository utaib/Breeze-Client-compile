package dev.breeze.packs;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PackOrderTest {

    private static final List<PackOrder.Entry> AVAILABLE = List.of(
            new PackOrder.Entry("vanilla", true, true, false),
            new PackOrder.Entry("fabric", true, true, false),
            new PackOrder.Entry("programmer_art", false, false, true),
            new PackOrder.Entry("file/Faithful.zip", false, false, true),
            new PackOrder.Entry("file/Dark UI", false, false, true),
            new PackOrder.Entry("server", false, true, true));

    @Test
    void topFirstBecomesBottomFirstWithRequiredPacksKept() {
        List<String> got = PackOrder.selection(AVAILABLE, List.of("file/Dark UI", "file/Faithful.zip"));
        assertEquals(List.of("vanilla", "fabric", "file/Faithful.zip", "file/Dark UI"), got,
                "the game's own packs stay at the bottom; the first asked for ends on top");
    }

    @Test
    void requiredPacksCannotBeTurnedOffOrMovedAndUnknownNamesAreLeftOut() {
        List<String> got = PackOrder.selection(AVAILABLE,
                List.of("vanilla", "file/Dark UI", "file/missing.zip", "file/Dark UI", "programmer_art"));
        assertEquals(List.of("vanilla", "fabric", "programmer_art", "file/Dark UI"), got);
        assertEquals(List.of("vanilla", "fabric"), PackOrder.selection(AVAILABLE, List.of()));
    }

    @Test
    void aFixedTopPackStaysOnTop() {
        List<String> got = PackOrder.selection(AVAILABLE, List.of("file/Faithful.zip", "server"));
        assertEquals(List.of("vanilla", "fabric", "file/Faithful.zip", "server"), got);
    }

    @Test
    void readsOnlyTextIdsFromARequest() {
        assertEquals(List.of("a", "file/b.zip"), PackOrder.requested(JsonParser.parseString(
                "{\"selected\":[\"a\", 3, null, \"\", {\"x\":1}, \"file/b.zip\"]}").getAsJsonObject()));
        assertEquals(List.of(), PackOrder.requested(JsonParser.parseString("{\"selected\":\"a\"}").getAsJsonObject()));
        assertEquals(List.of(), PackOrder.requested(null));
    }

    @Test
    void plainTextDropsFormattingCodes() {
        assertEquals("Faithful 32x\nFor 1.21", PackOrder.plain("§6Faithful §l32x\n§7For 1.21\u0007", 100));
        assertEquals("abc", PackOrder.plain("abcdef", 3));
        assertEquals("", PackOrder.plain(null, 10));
    }
}
