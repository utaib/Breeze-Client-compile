package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.music.SpotifyArt;
import dev.breeze.net.SpotifyPlayer;
import dev.breeze.settings.Setting;
import dev.breeze.ui.Glass;
import dev.breeze.ui.Palette;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * What is playing: cover, title, artist and how far into the song. From the
 * player's Spotify account when one is connected, else from whatever plays
 * music on this computer (Spotify, YouTube Music in a browser, ...), read
 * while this module is on ({@link dev.breeze.music.Music#tick}). The music
 * itself plays in that app; the Music screen (M by default) controls it.
 * Was "Spotify" up to 2.25.0, when it knew only Spotify.
 */
public class NowPlayingHud extends AbstractHudModule {

    private static final String GROUP = "Now Playing";
    private final Setting.Bool cover = new Setting.Bool("cover", "Cover art", GROUP, true);
    private final Setting.Bool artist = new Setting.Bool("artist", "Artist", GROUP, true);
    private final Setting.Bool progress = new Setting.Bool("progress", "Progress bar", GROUP, true);
    private final Setting.Bool hideIdle = new Setting.Bool("hideIdle", "Hide when nothing plays", GROUP, false);
    private final Setting.Int width = new Setting.Int("width", "Width", GROUP, 160, 100, 260, "");
    /** Also the Music screen's source switch: it is the Music player's choice, kept here so it is saved. */
    private final Setting.Mode source = new Setting.Mode("source", "Music from", GROUP,
            new String[]{"Spotify account, else this computer", "This computer"}, 0);

    private static NowPlayingHud instance;

    public static NowPlayingHud get() {
        return instance;
    }

    /** Whether the player chose this computer's music over a usable Spotify account. */
    public boolean prefersLocal() {
        return source.index == 1;
    }

    public void preferLocal(boolean on) {
        source.index = on ? 1 : 0;
    }

    public NowPlayingHud() {
        super("Now Playing", Category.HUD, "Shows the song playing in Spotify, YouTube Music or any player on this computer. Press M for the Music player.", KEY_NONE, 4, 168);
        add(cover);
        add(artist);
        add(progress);
        add(hideIdle);
        add(width);
        add(source);
        instance = this;
    }

    @Override
    public java.util.List<String> earlierNames() {
        return java.util.List.of("Spotify");
    }

    @Override
    protected boolean drawsShapes() {
        return true;
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        SpotifyPlayer.State s = SpotifyPlayer.state();
        SpotifyPlayer.Track t = s.track;
        boolean editing = dev.breeze.compat.ActiveScreen.get(mc) instanceof dev.breeze.menu.HudEditorScreen;
        if (t == null) {
            if (hideIdle.value && !editing) {
                bounds(0, 0);
                return;
            }
            String line = idleLine(s);
            int w = font.width(line);
            g.drawString(font, line, x, y, Palette.TEXT_SECONDARY, style().textShadow.value);
            bounds(w, font.lineHeight);
            return;
        }

        int art = cover.value ? 32 : 0;
        int gap = art > 0 ? 6 : 0;
        int textW = width.value - art - gap;
        int tx = x + art + gap;
        int ty = y;
        int textColor = style().textColor.argb;
        boolean shadow = style().textShadow.value;

        if (art > 0) {
            SpotifyArt.Art a = SpotifyArt.get(t.artworkSmall);
            if (a != null) {
                dev.breeze.compat.Draw.blit(g, a.id(), x, y, art, art, 0, 0, a.width(), a.height(), a.width(), a.height());
            } else {
                Glass.fillRounded(g, x, y, x + art, y + art, 3, Palette.alpha(Palette.BG_DEEP, 0xAA));
                g.drawString(font, "♪", x + (art - font.width("♪")) / 2, y + (art - font.lineHeight) / 2 + 1, 0xFF1ED760, false);
            }
        }

        g.drawString(font, font.plainSubstrByWidth(t.title, textW), tx, ty, textColor, shadow);
        ty += font.lineHeight + 1;
        if (artist.value && !t.artist.isEmpty()) {
            g.drawString(font, font.plainSubstrByWidth(t.artist, textW), tx, ty, Palette.TEXT_SECONDARY, shadow);
            ty += font.lineHeight + 1;
        }
        if (progress.value && t.durationMs > 0 && s.positionKnown()) {
            long now = System.currentTimeMillis();
            long p = s.progressNow(now);
            String times = SpotifyPlayer.time(p) + " / " + SpotifyPlayer.time(t.durationMs);
            int barY = ty + 2;
            int filled = (int) Math.round(textW * (p / (double) t.durationMs));
            g.fill(tx, barY, tx + textW, barY + 2, Palette.alpha(0xFFFFFFFF, 0x40));
            g.fill(tx, barY, tx + Math.max(0, Math.min(textW, filled)), barY + 2, s.playing ? 0xFF1ED760 : 0xFFAAAAAA);
            ty = barY + 5;
            g.drawString(font, times, tx, ty, Palette.TEXT_FAINT, shadow);
            ty += font.lineHeight;
        }
        bounds(width.value, Math.max(art, ty - y));
    }

    private static String idleLine(SpotifyPlayer.State s) {
        return switch (s.phase) {
            case READY -> s.deviceName == null ? "Spotify: nothing playing" : "Spotify: paused";
            case LOCAL -> s.deviceName == null ? "No music playing" : "Music: paused";
            case NOT_CONNECTED -> "Spotify: press M to connect";
            case BLOCKED -> "Spotify: account not allowed yet";
            case NOT_SIGNED_IN -> "Spotify: sign in to Breeze";
            case NOT_CONFIGURED -> "Spotify: not available";
            default -> "Music";
        };
    }
}
