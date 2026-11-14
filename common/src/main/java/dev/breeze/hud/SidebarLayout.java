package dev.breeze.hud;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * The sidebar scoreboard as Breeze draws it (Scoreboard module): which rows
 * Minecraft would show, in its order, and where the title, names and
 * numbers go. Widths come in from the game's font; the text itself, with the
 * server's colours and formats, stays in the version code.
 */
public final class SidebarLayout {

    /** Minecraft shows at most this many rows. */
    public static final int MAX_ROWS = 15;

    private SidebarLayout() {}

    public enum Align { LEFT, CENTER, RIGHT }

    /**
     * The rows Minecraft shows, top first: entries whose name starts with '#'
     * are hidden, the highest score is on top, equal scores by name A to Z
     * (ignoring case), at most {@link #MAX_ROWS}.
     */
    public static <T> List<T> pick(List<T> entries, ToIntFunction<T> value, Function<T, String> owner) {
        List<T> out = new ArrayList<>();
        for (T e : entries) {
            String o = owner.apply(e);
            if (o != null && !o.startsWith("#")) out.add(e);
        }
        out.sort(Comparator.comparingInt(value).reversed().thenComparing(owner, String.CASE_INSENSITIVE_ORDER));
        return out.size() > MAX_ROWS ? new ArrayList<>(out.subList(0, MAX_ROWS)) : out;
    }

    /**
     * Where things go, relative to the top left of the content: the title on
     * its own line, then one row per name with its number at the right edge.
     */
    public record Layout(int width, int height, int titleX, int[] rowY, int numberRight) {}

    /**
     * @param numberGap space between the longest name and its number
     * @param rowGap    extra space between rows (0 is Minecraft's own)
     * @param numbers   false to leave every number out
     */
    public static Layout layout(int titleW, int[] nameW, int[] numberW, int lineH, int rowGap, int numberGap,
                                Align titleAlign, boolean numbers) {
        int n = nameW.length;
        int width = titleW;
        for (int i = 0; i < n; i++) {
            int w = nameW[i] + (numbers && numberW[i] > 0 ? numberGap + numberW[i] : 0);
            width = Math.max(width, w);
        }
        int titleGap = 2;
        int[] rowY = new int[n];
        for (int i = 0; i < n; i++) rowY[i] = lineH + titleGap + i * (lineH + rowGap);
        int height = n == 0 ? lineH : rowY[n - 1] + lineH;
        int titleX = switch (titleAlign) {
            case LEFT -> 0;
            case CENTER -> (width - titleW) / 2;
            case RIGHT -> width - titleW;
        };
        return new Layout(width, height, titleX, rowY, width);
    }
}
