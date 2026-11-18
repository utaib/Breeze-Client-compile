package dev.breeze.ui;

import dev.breeze.compat.Draw;
import dev.breeze.compat.Ids;
import dev.breeze.compat.Resources;
import dev.breeze.cosmetics.ImageData;
import dev.breeze.hud.HotbarArt;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * Draws strips of Minecraft's own hotbar slots ({@link HotbarArt}): the
 * hotbar sprite from 1.20.2, gui/widgets.png before, whichever this game (or a
 * resource pack over it) has. Looked up once and kept for the session, on the
 * game's own thread, as {@link ModuleIconCache} does.
 */
public final class HotbarFrames {

    /** Where the hotbar is: the file, its size, and how much larger than Minecraft's own it is. */
    private record Found(ResourceLocation id, HotbarArt.Source source, int scale, int texW, int texH) {}

    /** A resource pack's hotbar sheet at 4x is about 100 KB; leave room. */
    private static final int MAX_BYTES = 1024 * 1024;

    private static Found found;
    private static boolean looked;

    private HotbarFrames() {}

    /** The file the slots come from, for the self-test; null when this game has none. */
    public static String source() {
        Found f = find();
        return f == null ? null : f.source().path() + " x" + f.scale();
    }

    /**
     * A strip of n slots at (x, y): across like the hotbar, or down. Plain
     * dark slots when no hotbar picture was found, so the items still sit in
     * something.
     */
    public static void draw(GuiGraphics g, int x, int y, int slots, boolean vertical) {
        Found f = find();
        if (f == null) {
            for (int k = 0; k < slots; k++) {
                int sx = vertical ? x : x + HotbarArt.PITCH * k;
                int sy = vertical ? y + HotbarArt.PITCH * k : y;
                g.fill(sx, sy, sx + HotbarArt.SLOT, sy + HotbarArt.SLOT, 0xFF000000);
                g.fill(sx + 1, sy + 1, sx + HotbarArt.SLOT - 1, sy + HotbarArt.SLOT - 1, 0xB0333333);
            }
            return;
        }
        int s = f.scale();
        for (HotbarArt.Blit b : HotbarArt.strip(slots, vertical)) {
            // With blending on: the inside of each slot is see-through, as in the hotbar.
            Draw.blit(g, f.id(), x + b.x(), y + b.y(), b.w(), b.h(),
                    (f.source().u() + b.u()) * s, (f.source().v() + b.v()) * s, b.w() * s, b.h() * s,
                    f.texW(), f.texH(), 1f);
        }
    }

    private static Found find() {
        if (looked) return found;
        looked = true;
        for (HotbarArt.Source src : HotbarArt.SOURCES) {
            ResourceLocation id = Ids.of("minecraft", src.path());
            int[] size = ImageData.pngSize(Resources.read(id, MAX_BYTES));
            if (size == null) continue;
            int scale = HotbarArt.scale(src, size[0], size[1]);
            if (scale > 0) {
                found = new Found(id, src, scale, size[0], size[1]);
                break;
            }
        }
        return found;
    }
}
