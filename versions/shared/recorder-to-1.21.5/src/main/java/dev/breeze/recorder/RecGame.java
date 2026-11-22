package dev.breeze.recorder;

import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.TitleScreen;

/**
 * The game calls the recorder makes outside the network (Minecraft 1.20.5 to 1.21.5): the
 * version, the clock, and leaving a world. Other versions carry their own
 * copy in versions/shared/recorder-to-*.
 */
final class RecGame {

    private RecGame() {}

    static String versionName() {
        return SharedConstants.getCurrentVersion().getName();
    }

    static int protocol() {
        return SharedConstants.getProtocolVersion();
    }

    static long millis() {
        return Util.getMillis();
    }

    /** Whether the screen is one Minecraft shows while it leaves a world. */
    static boolean leavingScreen(Minecraft mc) {
        return dev.breeze.compat.ActiveScreen.get(mc) instanceof GenericMessageScreen || dev.breeze.compat.ActiveScreen.get(mc) instanceof ProgressScreen
                || dev.breeze.compat.ActiveScreen.get(mc) instanceof dev.breeze.menu.SavingScreen;
    }

    /** Leaves the world the way the pause menu's Disconnect does, showing saving (Breeze's SavingScreen) while a singleplayer world is saved, then the title screen. */
    static void leaveWorld(Minecraft mc, net.minecraft.client.gui.screens.Screen saving) {
        mc.level.disconnect();
        mc.disconnect(saving, false);
        dev.breeze.compat.ActiveScreen.set(mc, new TitleScreen());
    }

    /** Closes a watched recording's connection and goes to the title screen. */
    static void toTitle(Minecraft mc) {
        mc.disconnect(new TitleScreen(), false);
    }

    static void toast(Minecraft mc, String title, String text) {
        RecToast.show(mc, title, text);
    }

    static int[] frameSize(Minecraft mc) {
        return RecFrames.size(mc);
    }

    static void readFrame(Minecraft mc, int w, int h, java.util.function.Consumer<java.nio.ByteBuffer> sink) {
        RecFrames.read(mc, w, h, sink);
    }
}
