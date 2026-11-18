package dev.breeze.hud;

import java.util.ArrayList;
import java.util.List;

/**
 * Slots cut from Minecraft's own hotbar picture, so a HUD can show items in
 * what looks like a piece of the hotbar (the Armor HUD's Hotbar look, 2.14.0).
 *
 * The hotbar is one 182 by 22 picture in every version: the sprite
 * {@code gui/sprites/hud/hotbar.png} from 1.20.2, and the top left of
 * {@code gui/widgets.png} (a 256 square sheet) before. Column 0 and 181 and
 * rows 0 and 21 are its black outline; slot k is the 20 pixels from 1 + 20k,
 * the item inside it at 3 + 20k. So a row of n slots is the first 1 + 20n
 * columns and then the last one, and a column of n slots is the same thing
 * across the rows. Nothing is copied: the game's (or a resource pack's)
 * picture is drawn.
 */
public final class HotbarArt {

    /** The hotbar picture's size in its own pixels, and one slot's step. */
    public static final int WIDTH = 182;
    public static final int HEIGHT = 22;
    public static final int PITCH = 20;
    /** One slot on its own, outline included. */
    public static final int SLOT = 22;
    /** Where the 16 pixel item sits inside a slot. */
    public static final int ITEM_INSET = 3;
    /** The most slots one strip can hold (the hotbar's nine). */
    public static final int MAX_SLOTS = 9;

    /**
     * Where the hotbar picture may be: {@code path} in the game's assets, the
     * hotbar at (u, v) of it, and the size the file has at Minecraft's own
     * resolution (a resource pack's may be a whole multiple of it).
     */
    public record Source(String path, int u, int v, int fileW, int fileH) {}

    /** Best first: the sprite (1.20.2 and later), then the old shared sheet. */
    public static final List<Source> SOURCES = List.of(
            new Source("textures/gui/sprites/hud/hotbar.png", 0, 0, WIDTH, HEIGHT),
            new Source("textures/gui/widgets.png", 0, 0, 256, 256));

    /**
     * One piece to copy: (w, h) at (x, y) relative to the strip, from (u, v)
     * of the hotbar, both in the hotbar's own pixels.
     */
    public record Blit(int x, int y, int w, int h, int u, int v) {}

    private HotbarArt() {}

    /**
     * How many times larger than Minecraft's own the file is, or 0 when a file
     * of that size is not this source (an animated sprite, a cropped sheet).
     */
    public static int scale(Source s, int w, int h) {
        if (w <= 0 || h <= 0 || w % s.fileW() != 0) return 0;
        int k = w / s.fileW();
        return h == s.fileH() * k ? k : 0;
    }

    /** The width and height of a strip of n slots. */
    public static int length(int slots) {
        return PITCH * slots + 2;
    }

    /** The pieces of the hotbar that make a strip of n slots, across or down. */
    public static List<Blit> strip(int slots, boolean vertical) {
        if (slots < 1 || slots > MAX_SLOTS) throw new IllegalArgumentException("slots " + slots);
        List<Blit> out = new ArrayList<>(vertical ? 6 : 2);
        int body = 1 + PITCH * slots;
        if (!vertical) {
            out.add(new Blit(0, 0, body, HEIGHT, 0, 0));
            out.add(new Blit(body, 0, 1, HEIGHT, WIDTH - 1, 0));
            return out;
        }
        // Down: the first slot's columns (outline, slot), then the right-hand
        // outline; its top outline row, the slot rows once per slot, the
        // bottom outline row.
        int across = 1 + PITCH;
        out.add(new Blit(0, 0, across, 1, 0, 0));
        out.add(new Blit(across, 0, 1, 1, WIDTH - 1, 0));
        out.add(new Blit(0, body, across, 1, 0, HEIGHT - 1));
        out.add(new Blit(across, body, 1, 1, WIDTH - 1, HEIGHT - 1));
        for (int k = 0; k < slots; k++) {
            int y = 1 + PITCH * k;
            out.add(new Blit(0, y, across, PITCH, 0, 1));
            out.add(new Blit(across, y, 1, PITCH, WIDTH - 1, 1));
        }
        return out;
    }

    /** Where the item in slot k of a strip goes, relative to the strip. */
    public static int itemOffset(int k) {
        return ITEM_INSET + PITCH * k;
    }
}
