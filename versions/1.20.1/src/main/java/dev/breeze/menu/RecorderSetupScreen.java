package dev.breeze.menu;

import com.mojang.blaze3d.platform.InputConstants;
import dev.breeze.compat.BreezeScreen;
import dev.breeze.compat.Widgets;
import dev.breeze.recorder.AudioLink;
import dev.breeze.recorder.RecorderPrefs;
import dev.breeze.ui.Glass;
import dev.breeze.ui.Palette;
import dev.breeze.ui.Rect;
import dev.breeze.ui.Spacing;
import dev.breeze.ui.UiRender;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The Recorder's preferences: shown the first time its key is pressed
 * (saving starts the recording at once), and from the Breeze menu after
 * that. What is recorded (the screen video always; the world replay unless
 * video only), the frame rate, the size, the quality, the sound (game and
 * microphone, which the Breeze launcher records), and the encoder.
 */
public class RecorderSetupScreen extends BreezeScreen {

    private final Screen parent;
    private final boolean fromKey;
    private final RecorderPrefs prefs;
    private AudioLink.Status sound;
    private Rect panel = new Rect(0, 0, 0, 0);
    private int soundCheck;

    public RecorderSetupScreen(Screen parent, boolean fromKey) {
        super(Component.literal("Recorder preferences"));
        this.parent = parent;
        this.fromKey = fromKey;
        // Edited on a copy: Cancel leaves the saved preferences as they were.
        RecorderPrefs saved = RecorderPrefs.get(net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath());
        prefs = new RecorderPrefs();
        prefs.chosen = saved.chosen;
        prefs.keepReplay = saved.keepReplay;
        prefs.fps = saved.fps;
        prefs.size = saved.size;
        prefs.quality = saved.quality;
        prefs.gameSound = saved.gameSound;
        prefs.mic = saved.mic;
        prefs.micDevice = saved.micDevice;
        prefs.hardware = saved.hardware;
    }

    private void readSound() {
        sound = AudioLink.status(this.minecraft.gameDirectory.toPath());
    }

    /** Rows from the panel's top (GUI units): one layout for init and render. */
    private static final int ROW_REPLAY = 36, NOTE_REPLAY = 59, ROW_RATE = 72, ROW_QUALITY = 96, ROW_SOUND = 120, ROW_DEVICE = 144, NOTE_SOUND = 168;

    @Override
    protected void init() {
        if (sound == null) readSound();
        panel = Rect.centered(this.width, this.height, Math.min(this.width - 20, 420), Math.min(this.height - 10, this.height >= 260 ? 222 : 208));
        int x = panel.x + Spacing.MD, w = panel.w - Spacing.MD * 2, half = (w - 6) / 2;
        addRenderableWidget(Widgets.button(Component.literal("World replay: " + (prefs.keepReplay ? "Keep it too" : "Video only")),
                b -> { prefs.keepReplay = !prefs.keepReplay; rebuildWidgets(); }, x, panel.y + ROW_REPLAY, w, 20));
        int y = panel.y + ROW_RATE;
        addRenderableWidget(Widgets.button(Component.literal("Frame rate: " + prefs.fps + " fps"), b -> { prefs.nextFps(); rebuildWidgets(); }, x, y, half, 20));
        addRenderableWidget(Widgets.button(Component.literal("Size: " + prefs.sizeLabel()), b -> { prefs.nextSize(); rebuildWidgets(); }, x + half + 6, y, half, 20));
        y = panel.y + ROW_QUALITY;
        addRenderableWidget(Widgets.button(Component.literal("Quality: " + prefs.qualityLabel()), b -> { prefs.nextQuality(); rebuildWidgets(); }, x, y, half, 20));
        addRenderableWidget(Widgets.button(Component.literal("Encoder: " + (prefs.hardware ? "Automatic" : "Processor only")),
                b -> { prefs.hardware = !prefs.hardware; rebuildWidgets(); }, x + half + 6, y, half, 20));
        y = panel.y + ROW_SOUND;
        boolean gameOk = sound.launcher() && sound.game(), micOk = sound.launcher() && sound.mic();
        Button game = addRenderableWidget(Widgets.button(Component.literal("Game sound: " + (prefs.gameSound && gameOk ? "On" : "Off")),
                b -> { prefs.gameSound = !prefs.gameSound; rebuildWidgets(); }, x, y, half, 20));
        game.active = gameOk;
        Button mic = addRenderableWidget(Widgets.button(Component.literal("Microphone: " + (prefs.mic && micOk ? "On" : "Off")),
                b -> { prefs.mic = !prefs.mic; rebuildWidgets(); }, x + half + 6, y, half, 20));
        mic.active = micOk;
        Button device = addRenderableWidget(Widgets.button(Component.literal("Microphone: " + deviceName()), b -> { nextDevice(); rebuildWidgets(); },
                x, panel.y + ROW_DEVICE, w, 20));
        device.active = micOk && prefs.mic && !sound.devices().isEmpty();
        int by = panel.bottom() - 26;
        addRenderableWidget(Widgets.button(Component.literal(fromKey ? "Save and record" : "Save"), b -> save(), panel.right() - Spacing.MD - 196, by, 110, 20));
        addRenderableWidget(Widgets.button(Component.literal("Cancel"), b -> onClose(), panel.right() - Spacing.MD - 80, by, 80, 20));
    }

    private String deviceName() {
        if (prefs.micDevice == null || prefs.micDevice.isEmpty()) return "System default";
        for (AudioLink.Device d : sound.devices()) if (d.id().equals(prefs.micDevice)) return d.name();
        return "System default";
    }

    private void nextDevice() {
        List<AudioLink.Device> list = sound.devices();
        if (list.isEmpty()) {
            prefs.micDevice = "";
            return;
        }
        int at = -1;
        for (int i = 0; i < list.size(); i++) if (list.get(i).id().equals(prefs.micDevice)) at = i;
        prefs.micDevice = at + 1 >= list.size() ? "" : list.get(at + 1).id();
    }

    private void save() {
        RecorderPrefs saved = RecorderPrefs.get(this.minecraft.gameDirectory.toPath());
        saved.chosen = true;
        saved.keepReplay = prefs.keepReplay;
        saved.fps = prefs.fps;
        saved.size = prefs.size;
        saved.quality = prefs.quality;
        saved.gameSound = prefs.gameSound;
        saved.mic = prefs.mic;
        saved.micDevice = prefs.micDevice == null ? "" : prefs.micDevice;
        saved.hardware = prefs.hardware;
        saved.save();
        dev.breeze.shots.ScreenshotChat.event("recorder-prefs-saved");
        if (fromKey) {
            dev.breeze.compat.ActiveScreen.set(this.minecraft, null);
            // The key's press, now that the preferences are known: the Recorder starts.
            for (dev.breeze.Module m : dev.breeze.ModuleManager.getModules()) {
                if (m instanceof dev.breeze.modules.Recorder) {
                    m.setEnabled(true);
                    break;
                }
            }
        } else {
            onClose();
        }
    }

    /** Self-test: what the player would pick (a frame rate; the rest as shown), then Save. */
    public void testSave(int fps) {
        prefs.fps = fps;
        prefs.keepReplay = true;
        save();
    }

    @Override
    public void tick() {
        super.tick();
        // The launcher may answer a moment after the screen opened.
        if (++soundCheck % 40 == 0) {
            AudioLink.Status before = sound;
            readSound();
            if (before.launcher() != sound.launcher() || before.devices().size() != sound.devices().size()) rebuildWidgets();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Glass.scrim(g, this.width, this.height);
        Glass.panel(g, panel);
        int x = panel.x + Spacing.MD, w = panel.w - Spacing.MD * 2;
        g.drawString(this.font, fromKey ? "Recorder: choose your preferences" : "Recorder preferences", x, panel.y + 8, Palette.TEXT_PRIMARY, true);
        UiRender.textClipped(g, this.font, "Your screen is always recorded, menus included.", x, panel.y + 22, w, Palette.TEXT_SECONDARY);
        UiRender.textClipped(g, this.font, prefs.keepReplay
                        ? "The world replay lets you render it later with another resource pack."
                        : "Video only: it cannot be rendered with another resource pack later.",
                x, panel.y + NOTE_REPLAY, w, Palette.TEXT_FAINT);
        String soundLine;
        if (!sound.launcher()) soundLine = sound.reason();
        else if (!sound.game() && !sound.mic()) soundLine = sound.reason().isEmpty() ? "Sound cannot be recorded on this computer." : sound.reason();
        else soundLine = "Game sound is only Minecraft's. Each can be muted when you export.";
        UiRender.textClipped(g, this.font, soundLine, x, panel.y + NOTE_SOUND, w, Palette.TEXT_FAINT);
        if (panel.h >= 222) UiRender.textClipped(g, this.font, "Change these later from the Recorder in the Breeze menu.", x, panel.bottom() - 40, w, Palette.TEXT_FAINT);
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
        if (this.minecraft != null) dev.breeze.compat.ActiveScreen.set(this.minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
