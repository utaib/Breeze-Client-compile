package dev.breeze.packs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resource packs in the in-game menu: the part with no Minecraft in it.
 *
 * The menu asks for a list of packs to have on, top first (the top one wins
 * where packs overlap), as Minecraft's own Resource Packs screen shows them.
 * This turns that into the list Minecraft's pack repository takes, bottom
 * first, keeping the rules that screen keeps: a required pack (the game's
 * own resources) stays on, a pack with a fixed place stays in it (at the
 * bottom or the top, as the pack says), names the game does not know are
 * left out. The game side applies the result through Minecraft's own
 * options, which save it and reload resources only when the list changed.
 */
public final class PackOrder {

    /** More than anyone has installed; a longer request is cut. */
    public static final int MAX_PACKS = 256;

    private PackOrder() {}

    /**
     * A pack as the repository knows it: {@code required} cannot be turned
     * off, {@code fixed} cannot be moved, {@code top} is where a fixed or
     * required pack goes (top or bottom).
     */
    public record Entry(String id, boolean required, boolean fixed, boolean top) {}

    /**
     * @param available every pack the game knows, in the repository's order
     * @param wantedTopFirst the ids the player wants on, top first
     * @return the ids to select, bottom first, as the repository takes them
     */
    public static List<String> selection(List<Entry> available, List<String> wantedTopFirst) {
        Map<String, Entry> known = new LinkedHashMap<>();
        for (Entry e : available) known.putIfAbsent(e.id(), e);
        Set<String> wanted = new LinkedHashSet<>();
        for (String id : wantedTopFirst) {
            if (id != null && known.containsKey(id) && wanted.size() < MAX_PACKS) wanted.add(id);
        }
        for (Entry e : known.values()) {
            if (e.required()) wanted.add(e.id());
        }
        List<String> bottom = new ArrayList<>();
        List<String> top = new ArrayList<>();
        List<String> movable = new ArrayList<>();
        for (Entry e : known.values()) {
            if (!wanted.contains(e.id()) || !e.fixed()) continue;
            (e.top() ? top : bottom).add(e.id());
        }
        for (String id : wanted) {
            if (!known.get(id).fixed()) movable.add(id);
        }
        List<String> out = new ArrayList<>(bottom);
        for (int i = movable.size() - 1; i >= 0; i--) out.add(movable.get(i));
        // Fixed top packs in the repository's order, the first of them on top.
        for (int i = top.size() - 1; i >= 0; i--) out.add(top.get(i));
        return out;
    }

    /**
     * Text from a pack's title or description as plain text: Minecraft's
     * formatting codes (a section sign and one character) taken out, line
     * breaks kept, at most {@code max} characters.
     */
    public static String plain(String s, int max) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(Math.min(s.length(), max));
        for (int i = 0; i < s.length() && b.length() < max; i++) {
            char c = s.charAt(i);
            if (c == '§') {
                i++;
                continue;
            }
            if (c == '\n' || c >= ' ') b.append(c);
        }
        return b.toString().strip();
    }
}
