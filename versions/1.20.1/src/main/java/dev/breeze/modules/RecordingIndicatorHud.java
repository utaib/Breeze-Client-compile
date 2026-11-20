package dev.breeze.modules;

import dev.breeze.Category;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

public class RecordingIndicatorHud extends AbstractHudModule {

    public RecordingIndicatorHud() {
        super("Recording Indicator", Category.HUD, "Shows a recording indicator.", KEY_NONE, 0, 0);
    }

    /** Top right until moved; it used to be drawn there whatever its position said. */
    @Override
    protected dev.breeze.hud.HudPlacement defaultPlacement() {
        return new dev.breeze.hud.HudPlacement(dev.breeze.hud.HudPlacement.H.RIGHT, dev.breeze.hud.HudPlacement.V.TOP, 6, 6);
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        // While Breeze's own recorder runs, how long it has been recording.
        long ms = dev.breeze.recorder.RecorderHooks.recordedMs();
        line(g, font, ms >= 0 ? "\u25CF REC " + dev.breeze.recorder.Timeline.clock(ms) : "\u25CF REC", 0xFFFF3333);
    }
}
