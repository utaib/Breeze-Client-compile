package dev.breeze.music;

import dev.breeze.net.SpotifyPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * When Spotify is read, and how the Music screen is opened.
 *
 * The player is only read while something on screen shows it: the Music
 * screen (every 1.5 s, so the seek bar and the song keep up), or the Now
 * Playing or Lyrics module in a world (every 5 s; the progress in between is
 * counted locally). Otherwise nothing goes out and nothing is started.
 */
public final class Music {

    private Music() {}

    public static void tick(Minecraft mc) {
        long every = 0;
        Screen screen = dev.breeze.compat.ActiveScreen.get(mc);
        dev.breeze.modules.NowPlayingHud hud = dev.breeze.modules.NowPlayingHud.get();
        dev.breeze.modules.LyricsHud lyrics = dev.breeze.modules.LyricsHud.get();
        if (screen instanceof MusicScreen) every = 1_500;
        // Lyrics follow the song: a local player is cheap to read, so a new song is noticed within two seconds.
        else if (mc.level != null && lyrics != null && lyrics.isEnabled()) {
            every = SpotifyPlayer.state().phase == SpotifyPlayer.Phase.LOCAL ? 2_000 : 5_000;
        } else if (mc.level != null && hud != null && hud.isEnabled()) every = 5_000;
        try {
            if (hud != null) SpotifyPlayer.preferLocal(hud.prefersLocal());
            SpotifyPlayer.tick(System.currentTimeMillis(), every);
        } catch (Throwable ignored) {
            // Never let the music player take a tick down.
        }
    }

    /** Opens the Music screen over whatever is showing, which it returns to. */
    public static void open(Minecraft mc) {
        Screen current = dev.breeze.compat.ActiveScreen.get(mc);
        if (current instanceof MusicScreen) return;
        dev.breeze.compat.ActiveScreen.set(mc, new MusicScreen(current));
    }
}
