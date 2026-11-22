package dev.breeze.modules;

import com.mojang.blaze3d.platform.InputConstants;
import dev.breeze.Category;
import dev.breeze.Module;
import dev.breeze.recorder.RecSession;
import dev.breeze.recorder.RecorderHooks;
import dev.breeze.recorder.RecorderPrefs;
import dev.breeze.shots.ScreenshotChat;
import net.minecraft.client.Minecraft;

/**
 * Breeze's recorder: its key starts recording, and pressing it again stops
 * and saves. A recording is the screen video (exactly what the player saw,
 * with game sound and microphone as separate tracks) and, unless the player
 * chose video only, the world replay beside it, which can be watched again
 * and rendered with other resource packs. The first press opens the
 * preferences (RecorderSetupScreen).
 * Only offered on versions the recorder reaches (RecorderHooks.supported).
 */
public class Recorder extends Module {

    public Recorder() {
        super("Recorder", Category.UTILITY,
                "Records your screen like a screen recorder, with game sound and microphone, and keeps a world replay to render again with other packs. Press its key to start, and again to stop.", InputConstants.KEY_F9);
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
        // The first press: the preferences come first; saving them starts the recording.
        if (!RecorderPrefs.get(mc.gameDirectory.toPath()).chosen) {
            setStateSilently(false);
            if (mc.level != null) dev.breeze.compat.ActiveScreen.set(mc, new dev.breeze.menu.RecorderSetupScreen(null, true));
            else ScreenshotChat.say(mc, "Join a world first, then start recording.");
            return;
        }
        String why = RecSession.start(mc);
        if (why != null) {
            if (mc.level != null) ScreenshotChat.say(mc, why);
            setStateSilently(false);
            return;
        }
        ScreenshotChat.say(mc, "Recording. Press the Recorder key again to stop and save.");
    }

    @Override
    protected void onDisable() {
        RecSession.stop(Minecraft.getInstance());
    }

    @Override
    protected void onTick(Minecraft mc) {
        // Stopped by itself (the world was left, or the disk could not keep up).
        if (isEnabled() && !RecSession.active()) setStateSilently(false);
    }
}
