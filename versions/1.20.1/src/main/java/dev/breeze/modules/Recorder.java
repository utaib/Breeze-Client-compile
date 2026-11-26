package dev.breeze.modules;

import com.mojang.blaze3d.platform.InputConstants;
import dev.breeze.Category;
import dev.breeze.Module;
import dev.breeze.recorder.RecorderHooks;
import dev.breeze.shots.ScreenshotChat;
import net.minecraft.client.Minecraft;

/**
 * Breeze's recorder: its key starts recording, and pressing it again stops
 * and saves. A recording keeps the game, not a video, so it can be watched
 * again in Breeze and exported later with other resource packs or shaders.
 * Only offered on versions the recorder reaches (RecorderHooks.supported).
 */
public class Recorder extends Module {

    public Recorder() {
        super("Recorder", Category.UTILITY,
                "Records your screen to an MP4 (and the world, to watch and render again). Press its key to start, and again to stop.", InputConstants.KEY_F9);
    }

    @Override
    protected void onEnable() {
        Minecraft mc = Minecraft.getInstance();
        // While a recording plays, the same key opens its playback bar.
        if (RecorderHooks.watching() != null) {
            setStateSilently(false);
            dev.breeze.compat.ActiveScreen.set(mc, new dev.breeze.menu.PlaybackScreen());
            return;
        }
        dev.breeze.recorder.CaptureSettings prefs = dev.breeze.recorder.CaptureSettings.get(mc.gameDirectory.toPath());
        if (!prefs.configured) {
            // The first press asks what to record; saving there starts recording.
            setStateSilently(false);
            if (mc.level != null) dev.breeze.compat.ActiveScreen.set(mc, new dev.breeze.menu.RecorderSetupScreen(null, true));
            return;
        }
        String why = RecorderHooks.startSession(mc);
        if (why != null) {
            if (mc.level != null) ScreenshotChat.say(mc, why);
            setStateSilently(false);
            return;
        }
        ScreenshotChat.say(mc, "Recording " + (prefs.keepReplay ? "the screen and the world replay" : "the screen")
                + " (" + prefs.fps + " fps). Press the Recorder key again to stop and save.");
    }

    @Override
    protected void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        String name = RecorderHooks.stopSession(mc);
        if (name != null) mc.execute(() -> dev.breeze.compat.Game.message(mc, ScreenshotChat.recordingLine(name)));
    }

    @Override
    protected void onTick(Minecraft mc) {
        // Stopped by itself (the world was left, or the disk could not keep up).
        if (isEnabled() && !RecorderHooks.sessionActive()) setStateSilently(false);
    }
}
