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
 * remaining out of maximum), a bar, or a bar with the percentage.
 *
 * Two looks. Breeze: icon, bar and text in a line per piece. Hotbar (2.14.0, a
 * creator tester's request): each piece in a slot cut from Minecraft's own
 * hotbar ({@link HotbarArt}), joined into one strip like a second hotbar, the
 * item drawn as the hotbar draws it. There "Bar" is Minecraft's own durability
 * bar on the item; the text formats put the number beside the strip (down) or
 * above each slot (across).
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
        BAR_AND_PERCENT("Bar and percent");

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

    /** {@code hotbar}: the Hotbar look; {@code icons} then does not apply (a slot always shows its item). */
    public record Options(Format format, boolean vertical, Align align, boolean icons, boolean names,
                          boolean empties, boolean colours, int gap, boolean hotbar) {
        public Options(Format format, boolean vertical, Align align, boolean icons, boolean names,
                       boolean empties, boolean colours, int gap) {
            this(format, vertical, align, icons, names, empties, colours, gap, false);
        }
    }

    /**
     * One piece, relative to the HUD origin. {@code slot} is the index into the
     * pieces passed in, so the caller can find the item to draw.
     * {@code decorations}: draw Minecraft's own durability bar and count on the
     * item (Hotbar look).
     */
    public record Cell(int slot, int x, int y, boolean icon, String text, int textX, int textY, int textColor,
                       boolean bar, int barX, int barY, int barFill, int barColor, boolean decorations) {}

    /**
     * A strip of hotbar slots to draw first, at (x, y) relative to the HUD
     * origin ({@link HotbarArt#strip}).
     */
    public record Frame(int x, int y, int slots, boolean vertical) {}

    public record Result(List<Cell> cells, int width, int height, List<Frame> frames) {
        public Result(List<Cell> cells, int width, int height) {
            this(cells, width, height, List.of());
        }
    }

    private ArmorHudLayout() {}

    public static Result layout(List<Piece> pieces, Options o, ToIntFunction<String> textWidth, int textColor) {
        if (o.hotbar()) return hotbar(pieces, o, textWidth, textColor);
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
            // With icons on, an empty slot kept in the list shows Minecraft's
            // empty-slot picture in its place (2.13.0 left that space blank).
            cells.add(new Cell(i, cx, cy, o.icons(), texts[i], textX, cy + (cellH - TEXT_H) / 2, colour,
                    bars[i], barX, cy + (cellH - BAR_H) / 2, barFill,
                    p.damageable() ? durabilityColor(p.left(), p.max()) : textColor, false));
            cursor += (o.vertical() ? cellH : widths[i]) + gap;
        }
        return new Result(cells, width, height);
    }

    /** The words for one piece in the chosen format. */
    public static String text(Piece p, Options o) {
        if (o.hotbar()) return hotbarText(p, o.format(), o.names());
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

    /**
     * The Hotbar look. All the slots shown make one strip, as the hotbar is
     * one; empty ones only when kept. Down, the words sit beside each slot
     * (left of the strip when aligned right). Across, they sit above each
     * slot, and then the slots stand apart, each in its own outline, far
     * enough for the widest words: those are measured at full durability, so
     * nothing moves as the armour wears.
     */
    private static Result hotbar(List<Piece> pieces, Options o, ToIntFunction<String> textWidth, int textColor) {
        List<Integer> shown = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            if (pieces.get(i).present() || o.empties()) shown.add(i);
        }
        int n = Math.min(shown.size(), HotbarArt.MAX_SLOTS);
        if (n == 0) return new Result(List.of(), 0, 0, List.of());

        int widest = 0;
        for (int k = 0; k < n; k++) {
            Piece p = pieces.get(shown.get(k));
            String full = hotbarText(new Piece(p.present(), p.damageable(), p.max(), p.max(), p.name(), p.count()),
                    o.format(), o.names());
            if (!full.isEmpty()) widest = Math.max(widest, textWidth.applyAsInt(full));
        }

        List<Cell> cells = new ArrayList<>(n);
        List<Frame> frames = new ArrayList<>(o.vertical() || widest == 0 ? 1 : n);
        int width;
        int height;
        if (o.vertical()) {
            boolean left = o.align() == Align.RIGHT && widest > 0;
            int stripX = left ? widest + INNER_GAP : 0;
            frames.add(new Frame(stripX, 0, n, true));
            for (int k = 0; k < n; k++) {
                int i = shown.get(k);
                Piece p = pieces.get(i);
                String text = hotbarText(p, o.format(), o.names());
                int itemY = HotbarArt.itemOffset(k);
                int textX = left ? stripX - INNER_GAP - textWidth.applyAsInt(text) : stripX + HotbarArt.SLOT + INNER_GAP;
                cells.add(hotbarCell(i, p, stripX + HotbarArt.ITEM_INSET, itemY, text, textX,
                        itemY + (ICON - TEXT_H) / 2, o, textColor));
            }
            width = HotbarArt.SLOT + (widest > 0 ? INNER_GAP + widest : 0);
            height = HotbarArt.length(n);
        } else if (widest == 0) {
            frames.add(new Frame(0, 0, n, false));
            for (int k = 0; k < n; k++) {
                int i = shown.get(k);
                cells.add(hotbarCell(i, pieces.get(i), HotbarArt.itemOffset(k), HotbarArt.ITEM_INSET, "", 0, 0,
                        o, textColor));
            }
            width = HotbarArt.length(n);
            height = HotbarArt.HEIGHT;
        } else {
            int gap = Math.max(0, o.gap());
            int pitch = Math.max(HotbarArt.SLOT + Math.max(2, gap), widest + Math.max(MIN_ACROSS_GAP, gap));
            int top = TEXT_H + 2;
            for (int k = 0; k < n; k++) {
                int i = shown.get(k);
                Piece p = pieces.get(i);
                int boxX = k * pitch + (pitch - HotbarArt.SLOT) / 2;
                frames.add(new Frame(boxX, top, 1, false));
                String text = hotbarText(p, o.format(), o.names());
                int textX = boxX + HotbarArt.SLOT / 2 - textWidth.applyAsInt(text) / 2;
                cells.add(hotbarCell(i, p, boxX + HotbarArt.ITEM_INSET, top + HotbarArt.ITEM_INSET, text, textX, 0,
                        o, textColor));
            }
            width = pitch * n;
            height = top + HotbarArt.SLOT;
        }
        return new Result(cells, width, height, frames);
    }

    private static Cell hotbarCell(int slot, Piece p, int x, int y, String text, int textX, int textY,
                                   Options o, int textColor) {
        int colour = p.present() && p.damageable() && o.colours() ? durabilityColor(p.left(), p.max()) : textColor;
        // Minecraft's bar for worn gear when the format has a bar, and always
        // the count (a stack of totems or arrows), as the hotbar draws them.
        boolean decorations = p.present() && (!p.damageable() || o.format().bar());
        return new Cell(slot, x, y, true, text, textX, textY, colour, false, 0, 0, 0,
                p.damageable() ? durabilityColor(p.left(), p.max()) : textColor, decorations);
    }

    /**
     * The words beside a slot in the Hotbar look: the durability in the
     * chosen format (none for Bar, which Minecraft draws on the item), after
     * the name when names are on. A stack's count is Minecraft's to draw, so
     * other items have words only for their name.
     */
    static String hotbarText(Piece p, Format f, boolean names) {
        if (!p.present()) return "";
        if (!p.damageable()) return names ? p.name() : "";
        String value = value(p, f);
        if (!names) return value;
        return value.isEmpty() ? p.name() : p.name() + " " + value;
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
