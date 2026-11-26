package dev.breeze.menu;

import com.mojang.blaze3d.platform.InputConstants;
import dev.breeze.compat.BreezeScreen;
import dev.breeze.compat.Widgets;
import dev.breeze.recorder.CaptureSettings;
import dev.breeze.ui.Glass;
import dev.breeze.ui.Palette;
import dev.breeze.ui.Rect;
import dev.breeze.ui.Spacing;
import dev.breeze.ui.UiRender;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The recorder's preferences: shown on the first press of the Recorder key
 * (saving there starts recording) and from the Recordings page later. The
 * screen video is always made; the world replay is kept too unless switched
 * off; frame rate, size, quality and encoder apply to the screen video.
 * Changes are kept only when saved.
 */
public class RecorderSetupScreen extends BreezeScreen {

    private final Screen parent;
    private final boolean startAfter;
    private final CaptureSettings saved;
    private boolean keepReplay, hardware;
    private int fps, size;
    private dev.breeze.recorder.VideoEncoder.Quality quality;
    private Rect panel = new Rect(0, 0, 0, 0);

    /** The screen shown now (self-test), or null. */
    public static volatile RecorderSetupScreen shown;

    public RecorderSetupScreen(Screen parent, boolean startAfter) {
        super(Component.literal("Recorder preferences"));
        this.parent = parent;
        this.startAfter = startAfter;
        this.saved = CaptureSettings.get(net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath());
        keepReplay = saved.keepReplay;
        hardware = saved.hardware;
        fps = saved.fps;
        size = saved.size;
        quality = saved.quality;
    }

    @Override
    protected void init() {
        shown = this;
        panel = Rect.centered(this.width, this.height, Math.min(this.width - 20, 340), Math.min(this.height - 20, 214));
        int x = panel.x + Spacing.MD, w = panel.w - Spacing.MD * 2;
        int y = panel.y + 44;
        addRenderableWidget(Widgets.button(Component.literal("World replay: " + (keepReplay ? "Kept too" : "Not kept")), b -> {
            keepReplay = !keepReplay;
            rebuildWidgets();
        }, x, y, w, 20));
        y += 24;
        int half = (w - 4) / 2;
        addRenderableWidget(Widgets.button(Component.literal("Frame rate: " + fps + " fps"), b -> {
            fps = next(CaptureSettings.RATES, fps);
            rebuildWidgets();
        }, x, y, half, 20));
        addRenderableWidget(Widgets.button(Component.literal("Size: " + (size == 0 ? "Game window" : size + "p")), b -> {
            size = next(CaptureSettings.SIZES, size);
            rebuildWidgets();
        }, x + half + 4, y, half, 20));
        y += 24;
        addRenderableWidget(Widgets.button(Component.literal("Quality: " + qualityLabel()), b -> {
            dev.breeze.recorder.VideoEncoder.Quality[] q = dev.breeze.recorder.VideoEncoder.Quality.values();
            quality = q[(quality.ordinal() + 1) % q.length];
            rebuildWidgets();
        }, x, y, half, 20));
        addRenderableWidget(Widgets.button(Component.literal(hardware ? "Encoder: Automatic" : "Encoder: Processor"), b -> {
            hardware = !hardware;
            rebuildWidgets();
        }, x + half + 4, y, half, 20));
        int by = panel.bottom() - 28;
        addRenderableWidget(Widgets.button(Component.literal(startAfter ? "Save and record" : "Save"), b -> save(), x, by, 110, 20));
        addRenderableWidget(Widgets.button(Component.literal("Cancel"), b -> onClose(), panel.right() - Spacing.MD - 70, by, 70, 20));
    }

    private String qualityLabel() {
        return switch (quality) { case STANDARD -> "Standard"; case HIGH -> "High"; case BEST -> "Best"; };
    }

    private static int next(int[] values, int v) {
        for (int i = 0; i < values.length; i++) if (values[i] == v) return values[(i + 1) % values.length];
        return values[0];
    }

    /** Keeps the choices and, on the first press, starts recording (also the self-test's Save). */
    public void save() {
        if (saved.hardware != hardware) dev.breeze.recorder.ScreenCapture.forgetEncoder();
        saved.configured = true;
        saved.keepReplay = keepReplay;
        saved.hardware = hardware;
        saved.fps = fps;
        saved.size = size;
        saved.quality = quality;
        saved.save();
        net.minecraft.client.Minecraft mc = this.minecraft != null ? this.minecraft : net.minecraft.client.Minecraft.getInstance();
        shown = null;
        dev.breeze.compat.ActiveScreen.set(mc, parent);
        if (startAfter) {
            for (dev.breeze.Module m : dev.breeze.ModuleManager.getModules()) {
                if (m.getName().equals("Recorder")) m.setEnabled(true);
            }
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Glass.panel(g, panel);
        int x = panel.x + Spacing.MD, w = panel.w - Spacing.MD * 2;
        UiRender.textClipped(g, this.font, "Recorder preferences", x, panel.y + 10, w, Palette.TEXT_PRIMARY);
        UiRender.textClipped(g, this.font, "Your screen is always recorded to an MP4, exactly as you see it.", x, panel.y + 24, w, Palette.TEXT_SECONDARY);
        UiRender.textClipped(g, this.font, "The world replay lets you watch again and render with other packs.", x, panel.y + 116, w, Palette.TEXT_SECONDARY);
        UiRender.textClipped(g, this.font, "Change these later on the Recordings page.", x, panel.y + 128, w, Palette.TEXT_SECONDARY);
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    protected boolean onKeyPressed(int key, int scanCode, int modifiers) {
        if (key == InputConstants.KEY_ESCAPE) {
            onClose();
            return true;
        }
        return superKeyPressed(key, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        shown = null;
        if (this.minecraft != null) dev.breeze.compat.ActiveScreen.set(this.minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
