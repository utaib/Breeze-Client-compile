package dev.breeze.menu;

import dev.breeze.compat.BreezeScreen;
import dev.breeze.ui.Glass;
import dev.breeze.ui.Palette;
import dev.breeze.ui.Rect;
import dev.breeze.ui.Spacing;
import dev.breeze.ui.UiRender;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Shown while a world is left so a recording can be watched or rendered:
 * a singleplayer world is saved first, which Minecraft does on its own
 * server and which can take half a minute on a slow disk with a large view
 * distance. Minecraft keeps drawing frames while it waits, so this says
 * what is happening, how many chunks the world had loaded, and for how long
 * it has been saving, instead of a still "Saving world".
 */
public class SavingScreen extends BreezeScreen {

    private final String then;
    private final boolean local;
    private final int chunks;
    private final long since = System.nanoTime();
    private Rect panel = new Rect(0, 0, 0, 0);

    /** then: what follows ("watch the recording", "render the video"); chunks: loaded chunks to save, or -1 when unknown. */
    public SavingScreen(String then, boolean local, int chunks) {
        super(Component.literal(local ? "Saving the world" : "Leaving the server"));
        this.then = then;
        this.local = local;
        this.chunks = chunks;
    }

    /** Seconds since the screen opened (self-test). */
    public long seconds() {
        return (System.nanoTime() - since) / 1_000_000_000L;
    }

    @Override
    protected void init() {
        panel = Rect.centered(this.width, this.height, Math.min(this.width - 20, 360), 92);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Glass.scrim(g, this.width, this.height);
        Glass.panel(g, panel);
        int x = panel.x + Spacing.MD, w = panel.w - Spacing.MD * 2;
        double s = (System.nanoTime() - since) / 1e9;
        g.drawString(this.font, local ? "Saving your world" : "Leaving the server", x, panel.y + 12, Palette.TEXT_PRIMARY, true);
        String what = local
                ? (chunks > 0 ? "Minecraft is writing the " + chunks + " loaded chunks to disk." : "Minecraft is writing the world to disk.")
                : "Closing the connection.";
        UiRender.textClipped(g, this.font, what, x, panel.y + 30, w, Palette.TEXT_SECONDARY);
        UiRender.textClipped(g, this.font, "Then Breeze will " + then + ".", x, panel.y + 42, w, Palette.TEXT_SECONDARY);
        // A bar that moves while Minecraft works (it does not say how far it is), and the time so far.
        int track = w;
        g.fill(x, panel.y + 62, x + track, panel.y + 65, Palette.BORDER_HOVER);
        int seg = Math.max(20, track / 5);
        int pos = (int) ((s * 0.6 % 1.0) * (track + seg)) - seg;
        g.fill(x + Math.max(0, pos), panel.y + 62, x + Math.min(track, pos + seg), panel.y + 65, Palette.ACCENT);
        UiRender.textClipped(g, this.font, String.format(java.util.Locale.ROOT, "%d s", (long) s), x, panel.y + 72, w, Palette.TEXT_FAINT);
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    protected boolean onKeyPressed(int key, int scanCode, int modifiers) {
        // Nothing to cancel: Minecraft finishes saving on its own.
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
