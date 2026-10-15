package dev.breeze.music;

import dev.breeze.Module;
import dev.breeze.ModuleManager;
import dev.breeze.net.SpotifyPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * When Spotify is read, and how the Music screen is opened.
 *
 * Spotify is only asked anything while something on screen shows it: the
 * Music screen (every 1.5 s, so the seek bar and the song keep up) or the
 * Spotify HUD module in a world (every 5 s; the progress in between is
 * counted locally). Otherwise nothing goes out.
 */
public final class Music {

    private static Module hud;

    private Music() {}

    public static void tick(Minecraft mc) {
        long every = 0;
        Screen screen = dev.breeze.compat.ActiveScreen.get(mc);
        if (screen instanceof MusicScreen) every = 1_500;
        else if (mc.level != null && hudOn()) every = 5_000;
        try {
            SpotifyPlayer.tick(System.currentTimeMillis(), every);
        } catch (Throwable ignored) {
            // Never let the music player take a tick down.
        }
    }

    private static boolean hudOn() {
        if (hud == null) {
            for (Module m : ModuleManager.getModules()) {
                if (m instanceof dev.breeze.modules.SpotifyHud) hud = m;
            }
        }
        return hud != null && hud.isEnabled();
    }

    /** Opens the Music screen over whatever is showing, which it returns to. */
    public static void open(Minecraft mc) {
        Screen current = dev.breeze.compat.ActiveScreen.get(mc);
        if (current instanceof MusicScreen) return;
        dev.breeze.compat.ActiveScreen.set(mc, new MusicScreen(current));
    }
}
