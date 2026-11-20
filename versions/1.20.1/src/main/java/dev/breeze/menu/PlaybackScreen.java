package dev.breeze.menu;

import com.mojang.blaze3d.platform.InputConstants;
import dev.breeze.compat.BreezeScreen;
import dev.breeze.compat.Widgets;
import dev.breeze.recorder.RecorderHooks;
import dev.breeze.recorder.Timeline;
import dev.breeze.shots.ScreenshotChat;
import dev.breeze.ui.Glass;
import dev.breeze.ui.Palette;
import dev.breeze.ui.Rect;
import dev.breeze.ui.UiRender;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

/**
 * The playback bar while a recording plays (the Recorder key opens it):
 * play or pause, ten seconds back or on, slower or faster, export to a
 * video, and leave. The recording keeps playing behind it; Escape closes
 * the bar and goes back to watching.
 */
public class PlaybackScreen extends BreezeScreen {

    private Rect bar = new Rect(0, 0, 0, 0);

    public PlaybackScreen() {
        super(Component.literal("Playback"));
    }

    @Override
    protected void init() {
        int w = Math.min(this.width - 20, 424);
        bar = new Rect((this.width - w) / 2, this.height - 58, w, 50);
        int x = bar.x + 8, y = bar.bottom() - 26;
        addRenderableWidget(Widgets.button(Component.literal("Play / Pause"), b -> RecorderHooks.control(this.minecraft, "toggle"), x, y, 84, 20));
        addRenderableWidget(Widgets.button(Component.literal("-10 s"), b -> RecorderHooks.control(this.minecraft, "back"), x + 88, y, 44, 20));
        addRenderableWidget(Widgets.button(Component.literal("+10 s"), b -> RecorderHooks.control(this.minecraft, "forward"), x + 136, y, 44, 20));
        addRenderableWidget(Widgets.button(Component.literal("Slower"), b -> RecorderHooks.control(this.minecraft, "slower"), x + 184, y, 52, 20));
        addRenderableWidget(Widgets.button(Component.literal("Faster"), b -> RecorderHooks.control(this.minecraft, "faster"), x + 240, y, 52, 20));
        addRenderableWidget(Widgets.button(Component.literal("Export"), b -> {
            Path p = RecorderHooks.watching();
            if (p == null) return;
            String why = RecorderHooks.export(this.minecraft, p, dev.breeze.recorder.ExportSettings.get(this.minecraft.gameDirectory.toPath()).fps, dev.breeze.recorder.ExportSettings.get(this.minecraft.gameDirectory.toPath()).quality);
            if (why != null) ScreenshotChat.say(this.minecraft, why);
        }, x + 296, y, 56, 20));
        addRenderableWidget(Widgets.button(Component.literal("Leave"), b -> RecorderHooks.control(this.minecraft, "leave"), x + 356, y, 52, 20));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Glass.panel(g, bar);
        String info = RecorderHooks.replayInfo();
        String line = "Not playing";
        if (info != null) {
            long pos = num(info, "pos"), len = num(info, "length");
            boolean playing = info.contains("playing=true");
            line = (playing ? "Playing " : "Paused ") + Timeline.clock(pos) + " / " + Timeline.clock(len) + "   "
                    + info.replaceAll(".*speed=([0-9.]+).*", "$1") + "x";
            int track = bar.w - 16;
            int done = len <= 0 ? 0 : (int) (track * Math.min(1.0, pos / (double) len));
            g.fill(bar.x + 8, bar.y + 20, bar.x + 8 + track, bar.y + 22, Palette.BORDER_HOVER);
            g.fill(bar.x + 8, bar.y + 20, bar.x + 8 + done, bar.y + 22, Palette.ACCENT);
        }
        UiRender.textClipped(g, this.font, line, bar.x + 8, bar.y + 7, bar.w - 16, Palette.TEXT_PRIMARY);
        super.render(g, mouseX, mouseY, partialTick);
    }

    private static long num(String info, String key) {
        for (String part : info.split(" ")) {
            if (part.startsWith(key + "=")) {
                try {
                    return (long) Double.parseDouble(part.substring(key.length() + 1));
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 0;
    }

    @Override
    protected boolean onKeyPressed(int key, int scanCode, int modifiers) {
        if (key == InputConstants.KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (key == InputConstants.KEY_SPACE) {
            RecorderHooks.control(this.minecraft, "toggle");
            return true;
        }
        return superKeyPressed(key, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) dev.breeze.compat.ActiveScreen.set(this.minecraft, null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
