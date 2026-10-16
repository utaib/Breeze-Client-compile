package dev.breeze.settings;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RenamesTest {

    @Test
    void theCurrentNameWins() {
        Set<String> saved = Set.of("Low Fire", "No Fire Overlay");
        assertEquals("Low Fire", Renames.savedKey("Low Fire", List.of("No Fire Overlay"), saved::contains));
    }

    @Test
    void anOlderSaveIsFoundUnderTheEarlierName() {
        Set<String> saved = Set.of("No Fire Overlay");
        assertEquals("No Fire Overlay", Renames.savedKey("Low Fire", List.of("No Fire Overlay"), saved::contains));
    }

    @Test
    void theNewestEarlierNameIsTriedFirst() {
        Set<String> saved = Set.of("Oldest", "Older");
        assertEquals("Older", Renames.savedKey("Now", List.of("Older", "Oldest"), saved::contains));
    }

    @Test
    void nothingSavedGivesNull() {
        assertNull(Renames.savedKey("Low Fire", List.of("No Fire Overlay"), k -> false));
        assertNull(Renames.savedKey("FPS", List.of(), k -> false));
    }
}
