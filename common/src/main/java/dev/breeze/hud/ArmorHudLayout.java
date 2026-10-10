package dev.breeze.hud;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Where everything in the Armor HUD goes, worked out without Minecraft so it can
 * be tested. The version module reads the player's armour, and what is in each
 * hand, into {@link Piece}s,
 * asks for a layout, and draws each {@link Cell}: the item icon, a durability
 * bar, and a line of text, all relative to the HUD's own origin.
 *
 * Formats, as the spec lists them: a percentage, the number (remaining, or
 * remaining out of maximum), a bar, or a bar with the percentage. And Vanilla
 * (2.14.0, a creator tester's request): only the items, with Minecraft's own
 * durability bar and stack count drawn on them as in the hotbar, no text.
 */
public final class ArmorHudLayout {

    public static final int ICON = 16;
    public static final int TEXT_H = 8;
    public static final int BAR_W = 24;
    public static final int BAR_H = 3;
    /** Between an icon, a bar and the text inside one piece. */
    public static final int INNER_GAP = 4;
    /** Pieces side by side need some air even at zero spacing, or the numbers run together. */
    public static final int MIN_ACROSS_GAP = 4;

    public enum Format {
        PERCENT("Percent"),
        DURABILITY("Durability"),
        REMAINING("Remaining"),
        BAR("Bar"),
        BAR_AND_PERCENT("Bar and percent"),
        /** Last, so a saved choice keeps its meaning (saved by name anyway). */
        VANILLA("Vanilla");

        public final String label;

        Format(String label) {
            this.label = label;
        }

        public static String[] labels() {
            Format[] all = values();
            String[] out = new String[all.length];
            for (int i = 0; i < all.length; i++) out[i] = all[i].label;
            return out;
        }

        public static Format of(String label) {
            for (Format f : values()) if (f.label.equalsIgnoreCase(label)) return f;
            return PERCENT;
        }

        boolean bar() { return this == BAR || this == BAR_AND_PERCENT; }

        /** Items only, decorated by Minecraft; always with icons. */
        public boolean vanilla() { return this == VANILLA; }
    }

    public enum Align { LEFT, CENTER, RIGHT;
        public static Align of(String label) {
            if ("Center".equalsIgnoreCase(label)) return CENTER;
            if ("Right".equalsIgnoreCase(label)) return RIGHT;
            return LEFT;
        }
    }

    /**
     * One slot: an armour piece or a hand. {@code left} and {@code max} matter
     * only when damageable; {@code count} only when not (a stack of arrows or
     * totems shows how many).
     */
    public record Piece(boolean present, boolean damageable, int left, int max, String name, int count) {
        public static final Piece EMPTY = new Piece(false, false, 0, 0, "", 0);

        public Piece(boolean present, boolean damageable, int left, int max, String name) {
            this(present, damageable, left, max, name, 1);
        }
    }

    public record Options(Format format, boolean vertical, Align align, boolean icons, boolean names,
                          boolean empties, boolean colours, int gap) {}

    /**
     * One piece, relative to the HUD origin. {@code slot} is the index into the
     * pieces passed in, so the caller can find the item to draw.
     * {@code decorations}: draw Minecraft's own durability bar and count on the
     * icon (Vanilla).
     */
    public record Cell(int slot, int x, int y, boolean icon, String text, int textX, int textY, int textColor,
                       boolean bar, int barX, int barY, int barFill, int barColor, boolean decorations) {}

    public record Result(List<Cell> cells, int width, int height) {}

    private ArmorHudLayout() {}

    public static Result layout(List<Piece> pieces, Options o, ToIntFunction<String> textWidth, int textColor) {
        if (o.format().vanilla() && !o.icons()) {
            // Vanilla is the items themselves; without icons there is nothing to show.
            o = new Options(o.format(), o.vertical(), o.align(), true, o.names(), o.empties(), o.colours(), o.gap());
        }
        List<Cell> cells = new ArrayList<>(pieces.size());
        int[] widths = new int[pieces.size()];
        int cellH = o.icons() ? ICON : TEXT_H;

        // First pass: what each piece shows and how wide it is.
        String[] texts = new String[pieces.size()];
        boolean[] bars = new boolean[pieces.size()];
        boolean[] shown = new boolean[pieces.size()];
        int count = 0;
        for (int i = 0; i < pieces.size(); i++) {
            Piece p = pieces.get(i);
            if (!p.present() && !o.empties()) continue;
            shown[i] = true;
            count++;
            texts[i] = text(p, o);
            bars[i] = p.present() && p.damageable() && o.format().bar();
            int w = 0;
            if (o.icons() && p.present()) w += ICON;
            if (bars[i]) w += (w > 0 ? INNER_GAP : 0) + BAR_W;
            if (!texts[i].isEmpty()) w += (w > 0 ? INNER_GAP : 0) + textWidth.applyAsInt(texts[i]);
            if (o.icons() && !p.present()) w = Math.max(w, ICON); // an empty slot keeps its place
            widths[i] = w;
        }
        if (count == 0) return new Result(List.of(), 0, 0);

        int gap = o.vertical() ? Math.max(0, o.gap()) : Math.max(MIN_ACROSS_GAP, o.gap());
        int total = 0;
        int widest = 0;
        for (int i = 0; i < pieces.size(); i++) {
            if (!shown[i]) continue;
            widest = Math.max(widest, widths[i]);
            total += widths[i];
        }
        int width = o.vertical() ? widest : total + gap * (count - 1);
        int height = o.vertical() ? cellH * count + gap * (count - 1) : cellH;

        // Second pass: positions.
        int cursor = 0;
        for (int i = 0; i < pieces.size(); i++) {
            if (!shown[i]) continue;
            Piece p = pieces.get(i);
            int cx = o.vertical() ? alignOffset(o.align(), widest, widths[i]) : cursor;
            int cy = o.vertical() ? cursor : 0;
            int at = cx;
            boolean icon = o.icons() && p.present();
            if (icon) at += ICON;
            int barX = 0;
            int barFill = 0;
            int colour = textColor;
            if (p.present() && p.damageable() && o.colours()) colour = durabilityColor(p.left(), p.max());
            if (bars[i]) {
                if (at > cx) at += INNER_GAP;
                barX = at;
                barFill = barFill(p.left(), p.max());
                at += BAR_W;
            }
            int textX = 0;
            if (!texts[i].isEmpty()) {
                if (at > cx) at += INNER_GAP;
                textX = at;
            }
            cells.add(new Cell(i, cx, cy, icon, texts[i], textX, cy + (cellH - TEXT_H) / 2, colour,
                    bars[i], barX, cy + (cellH - BAR_H) / 2, barFill,
                    p.damageable() ? durabilityColor(p.left(), p.max()) : textColor,
                    icon && o.format().vanilla()));
            cursor += (o.vertical() ? cellH : widths[i]) + gap;
        }
        return new Result(cells, width, height);
    }

    /** The words for one piece in the chosen format. */
    public static String text(Piece p, Options o) {
        // Vanilla: no words; Minecraft draws the bar and the count on the item.
        if (o.format().vanilla()) return o.names() && p.present() ? p.name() : "";
        if (!p.present()) return o.icons() ? "" : "-";
        String value = p.damageable() ? value(p, o.format()) : "";
        if (!p.damageable()) {
            String count = p.count() > 1 ? Integer.toString(p.count()) : "";
            if (o.names() || !o.icons()) return count.isEmpty() ? p.name() : p.name() + " " + count;
            return count;
        }
        if (o.names()) return value.isEmpty() ? p.name() : p.name() + " " + value;
        return value;
    }

    private static String value(Piece p, Format f) {
        switch (f) {
            case PERCENT:
            case BAR_AND_PERCENT:
                return percent(p.left(), p.max()) + "%";
            case DURABILITY:
                return Math.max(0, p.left()) + "/" + p.max();
            case REMAINING:
                return Integer.toString(Math.max(0, p.left()));
            default:
                return "";
        }
    }

    /**
     * Remaining durability as a whole percentage that never lies at the ends:
     * 100 only when untouched, 0 only when nothing is left. Plain rounding said
     * 100% for a piece that had taken a hit and 0% for one that still had a use.
     */
    public static int percent(int left, int max) {
        if (max <= 0) return 100;
        if (left <= 0) return 0;
        if (left >= max) return 100;
        int p = Math.round(left * 100f / max);
        return Math.max(1, Math.min(99, p));
    }

    /** Pixels of the bar to fill, at least one while anything is left. */
    public static int barFill(int left, int max) {
        if (max <= 0) return BAR_W;
        if (left <= 0) return 0;
        return Math.max(1, Math.min(BAR_W, Math.round(BAR_W * (float) left / max)));
    }

    /**
     * The colour Minecraft gives an item's own durability bar: a hue from red
     * (worn out) through yellow to green (new). Same formula as
     * ItemStack.getBarColor, so the HUD agrees with the inventory.
     */
    public static int durabilityColor(int left, int max) {
        float f = max <= 0 ? 1f : Math.max(0f, Math.min(1f, (float) left / max));
        float h = f / 3f;
        int i = (int) (h * 6f) % 6;
        float frac = h * 6f - (int) (h * 6f);
        float r, g, b;
        switch (i) {
            case 0: r = 1f; g = frac; b = 0f; break;
            case 1: r = 1f - frac; g = 1f; b = 0f; break;
            default: r = 0f; g = 1f; b = frac; break;
        }
        return 0xFF000000 | channel(r) << 16 | channel(g) << 8 | channel(b);
    }

    private static int channel(float v) {
        return Math.max(0, Math.min(255, (int) (v * 255f)));
    }

    private static int alignOffset(Align a, int widest, int w) {
        if (a == Align.CENTER) return (widest - w) / 2;
        if (a == Align.RIGHT) return widest - w;
        return 0;
    }
}
