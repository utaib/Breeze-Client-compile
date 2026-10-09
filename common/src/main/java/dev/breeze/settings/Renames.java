package dev.breeze.settings;

import java.util.List;
import java.util.function.Predicate;

/**
 * Saved module state is keyed by the module's name. When a module is renamed,
 * a player's saved choice is still under the old name; this finds it, so the
 * rename does not quietly turn their module off.
 */
public final class Renames {

    private Renames() {}

    /**
     * The key a module's saved values are under: its current name if saved,
     * otherwise the first earlier name that was, otherwise null.
     *
     * @param earlierNames newest first
     */
    public static String savedKey(String name, List<String> earlierNames, Predicate<String> saved) {
        if (saved.test(name)) return name;
        for (String earlier : earlierNames) {
            if (saved.test(earlier)) return earlier;
        }
        return null;
    }
}
