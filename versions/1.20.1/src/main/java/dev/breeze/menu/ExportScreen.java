package dev.breeze.menu;

import dev.breeze.compat.BreezeScreen;
import dev.breeze.compat.Widgets;
import dev.breeze.recorder.Exporter;
import dev.breeze.ui.Glass;
import dev.breeze.ui.Palette;
import dev.breeze.ui.Rect;
import dev.breeze.ui.Spacing;
import dev.breeze.ui.UiRender;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * A render in progress (Exporter): what is being made, how far it is, the
 * time left, and Cancel, the only thing that stops it. Keys do nothing here
 * (Escape included), and the exporter puts this screen back if anything
 * else opens. The frames are read before this screen is drawn, so it is
 * never in the video.
 */
public class ExportScreen extends BreezeScreen {

    private Rect panel = new Rect(0, 0, 0, 0);

    public ExportScreen() {
        super(Component.literal("Rendering"));
    }

    @Override
    protected void init() {
        panel = new Rect((this.width - Math.min(this.width - 20, 320)) / 2, this.height - 84, Math.min(this.width - 20, 320), 74);
        addRenderableWidget(Widgets.button(Component.literal("Cancel"), b -> Exporter.cancel(), panel.right() - Spacing.MD - 70, panel.bottom() - 26, 70, 20));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // No scrim: the world being rendered stays in view above the panel.
        Glass.panel(g, panel);
        int x = panel.x + Spacing.MD, w = panel.w - Spacing.MD * 2;
        String what = Exporter.runningSummary();
        g.drawString(this.font, "Rendering" + (what == null ? "" : " " + what), x, panel.y + 10, Palette.TEXT_PRIMARY, true);
        long pct = Exporter.progress, left = Exporter.secondsLeft;
        String line = Exporter.runningStage() + (pct > 0 ? ", " + pct + "%" : "")
                + (left >= 0 ? String.format(java.util.Locale.ROOT, ", about %d:%02d left", left / 60, left % 60) : "");
        UiRender.textClipped(g, this.font, line, x, panel.y + 24, w, Palette.TEXT_SECONDARY);
        g.fill(x, panel.y + 38, x + w, panel.y + 41, Palette.BORDER_HOVER);
        g.fill(x, panel.y + 38, x + (int) (w * Math.min(100, pct) / 100), panel.y + 41, Palette.ACCENT);
        UiRender.textClipped(g, this.font, "Keys and menus do nothing until it is done.", x, panel.bottom() - 20, w - 80, Palette.TEXT_FAINT);
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    protected boolean onKeyPressed(int key, int scanCode, int modifiers) {
        // Every key is taken here, Escape included: only Cancel stops a render.
        return true;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
