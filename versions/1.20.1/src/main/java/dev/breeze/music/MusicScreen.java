package dev.breeze.music;

import com.mojang.blaze3d.platform.InputConstants;
import dev.breeze.compat.BreezeScreen;
import dev.breeze.net.SpotifyPlayer;
import dev.breeze.ui.Glass;
import dev.breeze.ui.Palette;
import dev.breeze.ui.Rect;
import dev.breeze.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * The Music player: the player's music, controlled from inside the game.
 *
 * Two sources: the player's Spotify account (through the Breeze API), or
 * whatever plays music on this computer (Spotify, YouTube Music in a
 * browser, ...). The account is used when it is connected; a switch in the
 * header changes to this computer, which is also what everyone without a
 * usable account gets.
 *
 * Opened with M (Controls, under Breeze) or from the Breeze menus. Left: what
 * is playing, a seek bar, play, pause, skip, shuffle, repeat and volume.
 * Right: search Spotify, the account's playlists and devices (Spotify plays
 * on one of them: this computer's Spotify app, a phone, a speaker), and the
 * song's lyrics with the Lyrics module's controls.
 * A native screen on every version, so it opens at once, in a world or not,
 * without the embedded browser.
 *
 * It does not pause singleplayer: changing the song is not a reason to stop
 * the world.
 */
public final class MusicScreen extends BreezeScreen {

    private static final int GREEN = 0xFF1ED760;
    private static final int ROW = 22;

    private enum Tab { SEARCH, PLAYLISTS, DEVICES, LYRICS }

    /** A control drawn this frame and what a click on it does (the header's switch, the Lyrics tab's buttons). */
    private record Hit(int x, int y, int w, int h, Runnable run) {}

    private final List<Hit> hits = new ArrayList<>();
    private int lyricsScroll;

    private final Screen parent;
    private int px, py, pw, ph;
    private Tab tab = Tab.SEARCH;
    private String query = "";
    private boolean typing = true;
    private volatile SpotifyPlayer.Listing listing = new SpotifyPlayer.Listing(List.of(), null, null);
    private volatile boolean loading;
    private int scroll;
    /** While the volume or seek bar is held: its value, sent on release. -1 when not dragging. */
    private int dragVolume = -1;
    private double dragSeek = -1;
    private String connectProblem;
    /** The "not open anywhere" message the Devices tab was last opened for, so it opens once per message. */
    private long devicesShownFor;

    public MusicScreen(Screen parent) {
        super(Component.literal("Music"));
        this.parent = parent;
        SpotifyPlayer.recheck();
    }

    @Override
    protected void init() {
        pw = Math.min(this.width - 24, 470);
        ph = Math.min(this.height - 24, 256);
        px = (this.width - pw) / 2;
        py = (this.height - ph) / 2;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        dev.breeze.compat.ActiveScreen.set(Minecraft.getInstance(), parent);
    }

    // ── layout ──────────────────────────────────────────────────────────────

    private int lx() { return px + 12; }
    private int lw() { return Math.min(196, (pw - 36) / 2); }
    private int rx() { return lx() + lw() + 12; }
    private int rw() { return px + pw - 12 - rx(); }
    private int seekY() { return py + 104; }
    private int controlsY() { return py + 122; }
    private int volumeY() { return py + 148; }
    private int listTop() { return py + (tab == Tab.SEARCH ? 70 : 50); }

    /**
     * The right column's tabs: [x, w] each. Four of them do not fit a small
     * window (427 pixels across at 854x480) with the usual padding, so the
     * padding gives way before the last tab would leave the panel.
     */
    private int[][] tabs() {
        Tab[] all = Tab.values();
        int text = 0;
        for (Tab t : all) text += this.font.width(tabLabel(t));
        int gap = 4;
        int pad = 12;
        int room = rw() - text - gap * (all.length - 1);
        if (room < pad * all.length) {
            gap = 3;
            pad = Math.max(4, (rw() - text - gap * (all.length - 1)) / all.length);
        }
        int[][] out = new int[all.length][];
        int x = rx();
        for (int i = 0; i < all.length; i++) {
            int w = this.font.width(tabLabel(all[i])) + pad;
            out[i] = new int[]{x, w};
            x += w + gap;
        }
        return out;
    }

    private static String tabLabel(Tab t) {
        return switch (t) {
            case SEARCH -> "Search";
            case PLAYLISTS -> "Playlists";
            case DEVICES -> "Devices";
            case LYRICS -> "Lyrics";
        };
    }
    private int visibleRows() { return Math.max(1, (py + ph - 12 - listTop()) / ROW); }

    /** The five transport buttons, laid out across the left column: [x, w] each. */
    private int[][] controls(SpotifyPlayer.State s) {
        String[] labels = labels(s);
        int gap = 4;
        int total = -gap;
        int[] widths = new int[labels.length];
        for (int i = 0; i < labels.length; i++) {
            widths[i] = this.font.width(labels[i]) + 10;
            total += widths[i] + gap;
        }
        int x = lx() + Math.max(0, (lw() - total) / 2);
        int[][] out = new int[labels.length][];
        for (int i = 0; i < labels.length; i++) {
            out[i] = new int[]{x, widths[i]};
            x += widths[i] + gap;
        }
        return out;
    }

    private static String[] labels(SpotifyPlayer.State s) {
        String repeat = switch (s.repeat) {
            case "context" -> "Loop";
            case "track" -> "Loop 1";
            default -> "Loop";
        };
        return new String[]{"Shuffle", "<<", s.playing ? "Pause" : "Play", ">>", repeat};
    }

    // ── drawing ─────────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Glass.scrim(g, this.width, this.height);
        Glass.panel(g, new Rect(px, py, pw, ph), Theme.panelBg(), Palette.BORDER);
        SpotifyPlayer.State s = SpotifyPlayer.state();
        hits.clear();

        g.drawString(this.font, "Music", px + 14, py + 10, Palette.TEXT_PRIMARY, false);
        String who = s.phase != SpotifyPlayer.Phase.LOCAL ? (s.displayName != null ? "Spotify, " + s.displayName : "Spotify")
                : s.deviceName == null ? "This computer"
                : SpotifyPlayer.LOCAL_DEVICE.equals(s.deviceName) ? "Music on this computer" : s.deviceName + " on this computer";
        int right = px + pw - 12 - this.font.width("Esc to close");
        g.drawString(this.font, "Esc to close", right, py + 10, Palette.TEXT_FAINT, false);
        // The source switch, for a player whose Spotify account is usable:
        // the account, or this computer (YouTube Music and other players).
        boolean chosen = s.phase == SpotifyPlayer.Phase.LOCAL && SpotifyPlayer.CHOSEN.equals(s.blocked);
        if (s.phase == SpotifyPlayer.Phase.READY || chosen) {
            String label = chosen ? "Use Spotify account" : "Use this computer";
            int bw = this.font.width(label) + 10;
            int bx = right - 8 - bw;
            button(g, bx, py + 6, bw, label, mouseX, mouseY, false);
            hits.add(new Hit(bx, py + 6, bw, 16, () -> setSource(!chosen)));
            right = bx;
        }
        int whoX = px + 14 + this.font.width("Music") + 8;
        g.drawString(this.font, this.font.plainSubstrByWidth(who, Math.max(20, right - 8 - whoX)), whoX, py + 10, GREEN, false);

        drawNowPlaying(g, s, mouseX, mouseY);
        drawRight(g, s, mouseX, mouseY);
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void drawNowPlaying(GuiGraphics g, SpotifyPlayer.State s, int mouseX, int mouseY) {
        int x = lx(), y = py + 28, w = lw();
        String problem = SpotifyPlayer.explain(s);
        if (problem != null) {
            int ty = y;
            for (String line : wrap(problem, w)) {
                g.drawString(this.font, line, x, ty, Palette.TEXT_SECONDARY, false);
                ty += 11;
            }
            ty += 6;
            if (s.phase == SpotifyPlayer.Phase.NOT_CONNECTED) {
                button(g, x, ty, 110, "Connect Spotify", mouseX, mouseY, true);
                ty += 24;
                if (connectProblem != null) {
                    for (String line : wrap(connectProblem, w)) {
                        g.drawString(this.font, line, x, ty, 0xFFFFB454, false);
                        ty += 11;
                    }
                }
            }
            if (s.phase != SpotifyPlayer.Phase.UNKNOWN) button(g, x, ty, 80, "Check again", mouseX, mouseY, false);
            return;
        }

        SpotifyPlayer.Track t = s.track;
        int art = 64;
        SpotifyArt.Art a = t == null ? null : SpotifyArt.get(t.artworkSmall);
        if (a != null) {
            dev.breeze.compat.Draw.blit(g, a.id(), x, y, art, art, 0, 0, a.width(), a.height(), a.width(), a.height());
        } else {
            Glass.fillRounded(g, x, y, x + art, y + art, Glass.RADIUS_SM, Palette.alpha(Palette.BG_DEEP, 0xCC));
            g.drawString(this.font, "♪", x + (art - this.font.width("♪")) / 2, y + (art - 8) / 2, GREEN, false);
        }
        int tx = x + art + 8, tw = w - art - 8, ty = y + 2;
        if (t == null) {
            g.drawString(this.font, "Nothing playing", tx, ty, Palette.TEXT_PRIMARY, false);
            for (String line : wrap(s.phase == SpotifyPlayer.Phase.LOCAL ? "Play a song in any player on this computer."
                    : "Play something below, or open Spotify on any device.", tw)) {
                ty += 11;
                g.drawString(this.font, line, tx, ty, Palette.TEXT_FAINT, false);
            }
        } else {
            for (String line : wrap(t.title, tw, 2)) {
                g.drawString(this.font, line, tx, ty, Palette.TEXT_PRIMARY, false);
                ty += 11;
            }
            g.drawString(this.font, this.font.plainSubstrByWidth(t.artist, tw), tx, ty, Palette.TEXT_SECONDARY, false);
            ty += 11;
            if (t.album != null) {
                g.drawString(this.font, this.font.plainSubstrByWidth(t.album, tw), tx, ty, Palette.TEXT_FAINT, false);
            }
        }
        if (s.deviceName != null) {
            String on = s.phase == SpotifyPlayer.Phase.LOCAL && !SpotifyPlayer.LOCAL_DEVICE.equals(s.deviceName) ? "in " : "on ";
            g.drawString(this.font, this.font.plainSubstrByWidth(on + s.deviceName, tw), tx, y + art - 9, Palette.TEXT_FAINT, false);
        }

        // Seek bar and times.
        long now = System.currentTimeMillis();
        long duration = t == null ? 0 : t.durationMs;
        long position = dragSeek >= 0 ? (long) (dragSeek * duration) : s.progressNow(now);
        int by = seekY();
        // A local player that tells no position or length: no bar then.
        if (s.full() || (duration > 0 && s.positionKnown())) {
            boolean overSeek = s.full() && t != null && inside(mouseX, mouseY, x, by - 3, w, 9);
            g.fill(x, by, x + w, by + 3, Palette.alpha(0xFFFFFFFF, 0x33));
            if (duration > 0) {
                int filled = (int) Math.round(w * Math.min(1.0, position / (double) duration));
                g.fill(x, by, x + filled, by + 3, s.playing ? GREEN : 0xFFB0B0B0);
                if (overSeek || dragSeek >= 0) g.fill(x + filled - 1, by - 2, x + filled + 2, by + 5, Palette.TEXT_PRIMARY);
            }
            g.drawString(this.font, SpotifyPlayer.time(position), x, by + 6, Palette.TEXT_FAINT, false);
            String end = SpotifyPlayer.time(duration);
            g.drawString(this.font, end, x + w - this.font.width(end), by + 6, Palette.TEXT_FAINT, false);
        }

        // Transport.
        boolean can = s.canControl();
        int[][] c = controls(s);
        String[] labels = labels(s);
        for (int i = 0; i < c.length; i++) {
            boolean on = (i == 0 && s.shuffle) || (i == 4 && !"off".equals(s.repeat));
            boolean enabled = can && (s.full() || (i >= 1 && i <= 3));
            button(g, c[i][0], controlsY(), c[i][1], labels[i], mouseX, mouseY, on, enabled);
        }

        // The local app: where the music comes from, and what connecting adds.
        if (s.phase == SpotifyPlayer.Phase.LOCAL) {
            drawLocalNote(g, s, mouseX, mouseY, now);
            return;
        }

        // Volume.
        int vy = volumeY();
        g.drawString(this.font, "Volume", x, vy, Palette.TEXT_SECONDARY, false);
        int vx = x + 44, vw = w - 44 - 30;
        int vol = dragVolume >= 0 ? dragVolume : s.volume;
        g.fill(vx, vy + 3, vx + vw, vy + 6, Palette.alpha(0xFFFFFFFF, 0x33));
        if (vol >= 0) {
            int filled = vw * vol / 100;
            g.fill(vx, vy + 3, vx + filled, vy + 6, can && s.volumeSupported ? Theme.primary() : 0xFF808080);
            g.fill(vx + filled - 1, vy + 1, vx + filled + 2, vy + 8, Palette.TEXT_PRIMARY);
            g.drawString(this.font, vol + "%", vx + vw + 6, vy, Palette.TEXT_FAINT, false);
        } else {
            g.drawString(this.font, "n/a", vx + vw + 6, vy, Palette.TEXT_FAINT, false);
        }

        // What Spotify said last.
        int my = vy + 18;
        if (Boolean.FALSE.equals(s.premium)) {
            for (String line : wrap("Spotify Premium is needed to control music. You can still see what is playing here and on the HUD.", w)) {
                g.drawString(this.font, line, x, my, 0xFFFFB454, false);
                my += 11;
            }
        } else if (s.message != null && now - s.messageAt < 12_000) {
            for (String line : wrap(s.message, w, 4)) {
                g.drawString(this.font, line, x, my, 0xFFFFB454, false);
                my += 11;
            }
        }
    }

    /** Under the local player: its note, a Connect button when that would add the full controls, Spotify's last word. */
    private void drawLocalNote(GuiGraphics g, SpotifyPlayer.State s, int mouseX, int mouseY, long now) {
        int x = lx(), w = lw(), y = volumeY();
        for (String line : wrap(SpotifyPlayer.localNote(s), w)) {
            g.drawString(this.font, line, x, y, Palette.TEXT_FAINT, false);
            y += 11;
        }
        if (localConnectY(s) >= 0) {
            button(g, x, localConnectY(s), 110, "Connect Spotify", mouseX, mouseY, false);
            y = localConnectY(s) + 22;
            if (connectProblem != null) {
                for (String line : wrap(connectProblem, w)) {
                    g.drawString(this.font, line, x, y, 0xFFFFB454, false);
                    y += 11;
                }
            }
        }
        if (s.message != null && now - s.messageAt < 12_000) {
            for (String line : wrap(s.message, w, 3)) {
                g.drawString(this.font, line, x, y, 0xFFFFB454, false);
                y += 11;
            }
        }
    }

    /** Where the local player's Connect button is, or -1 when it has none (only a player with no Spotify connected gets one). */
    private int localConnectY(SpotifyPlayer.State s) {
        if (s.phase != SpotifyPlayer.Phase.LOCAL || !"NOT_CONNECTED".equals(s.blocked)) return -1;
        return volumeY() + wrap(SpotifyPlayer.localNote(s), lw()).size() * 11 + 4;
    }

    private void drawRight(GuiGraphics g, SpotifyPlayer.State s, int mouseX, int mouseY) {
        int x = rx(), y = py + 28, w = rw();
        Glass.fillRounded(g, x - 4, y - 4, x + w + 4, py + ph - 8, Glass.RADIUS_SM, Palette.alpha(Palette.BG_DEEP, 0x33));
        if (s.phase == SpotifyPlayer.Phase.LOCAL) {
            // No account to search with: the song's lyrics have the column.
            g.drawString(this.font, "Lyrics", x, y + 4, Palette.TEXT_PRIMARY, false);
            drawLyrics(g, s, x, y + 20, w, mouseX, mouseY);
            return;
        }
        if (s.phase != SpotifyPlayer.Phase.READY) {
            int ty = y;
            for (String line : wrap("Your music appears here once Spotify is connected.", w)) {
                g.drawString(this.font, line, x, ty, Palette.TEXT_FAINT, false);
                ty += 11;
            }
            return;
        }
        int[][] tabs = tabs();
        for (Tab t : Tab.values()) {
            button(g, tabs[t.ordinal()][0], y, tabs[t.ordinal()][1], tabLabel(t), mouseX, mouseY, tab == t);
        }
        if (tab == Tab.LYRICS) {
            drawLyrics(g, s, x, y + 22, w, mouseX, mouseY);
            return;
        }
        if (tab == Tab.SEARCH) drawInput(g, x, y + 20, w, query, typing, "Song or artist, then Enter");

        int top = listTop();
        SpotifyPlayer.Listing l = listing;
        if (loading) {
            g.drawString(this.font, "Loading", x, top + 4, Palette.TEXT_FAINT, false);
            return;
        }
        if (l.problem != null) {
            int ty = top + 4;
            for (String line : wrap(l.problem, w, 4)) {
                g.drawString(this.font, line, x, ty, 0xFFFFB454, false);
                ty += 11;
            }
            if ("RECONNECT_REQUIRED".equals(l.reason)) button(g, x, ty + 4, 110, "Connect again", mouseX, mouseY, true);
            return;
        }
        if (l.items.isEmpty()) {
            String empty = switch (tab) {
                case SEARCH -> query.isBlank() ? "Search for a song to play it." : "Nothing found.";
                case PLAYLISTS -> "No playlists on this account.";
                case DEVICES -> "No devices. Open Spotify on this computer or your phone.";
                default -> "";
            };
            int ty = top + 4;
            for (String line : wrap(empty, w)) {
                g.drawString(this.font, line, x, ty, Palette.TEXT_FAINT, false);
                ty += 11;
            }
            return;
        }
        int rows = visibleRows();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, l.items.size() - rows)));
        for (int i = 0; i < rows && scroll + i < l.items.size(); i++) {
            SpotifyPlayer.Item it = l.items.get(scroll + i);
            int ry = top + i * ROW;
            boolean hover = inside(mouseX, mouseY, x - 2, ry, w + 4, ROW - 2);
            boolean current = tab == Tab.DEVICES ? it.active : s.track != null && it.uri != null && it.uri.equals(s.track.uri);
            if (hover || current) {
                Glass.fillRounded(g, x - 2, ry, x + w + 2, ry + ROW - 2, Glass.RADIUS_SM,
                        current ? Theme.withAlpha(Theme.primary(), 0x33) : Theme.cardHover());
            }
            String right = it.durationMs > 0 ? SpotifyPlayer.time(it.durationMs) : tab == Tab.DEVICES && it.active ? "playing" : "";
            int rightW = right.isEmpty() ? 0 : this.font.width(right) + 6;
            g.drawString(this.font, this.font.plainSubstrByWidth(it.name, w - rightW - 4), x + 2, ry + 2, Palette.TEXT_PRIMARY, false);
            g.drawString(this.font, this.font.plainSubstrByWidth(it.detail, w - 4), x + 2, ry + 11, Palette.TEXT_FAINT, false);
            if (!right.isEmpty()) g.drawString(this.font, right, x + w - rightW + 4, ry + 2, Palette.TEXT_FAINT, false);
        }
        if (l.items.size() > rows) {
            String more = (scroll + 1) + "-" + Math.min(l.items.size(), scroll + rows) + " of " + l.items.size();
            g.drawString(this.font, more, x + w - this.font.width(more), py + ph - 18, Palette.TEXT_FAINT, false);
        }
    }

    /**
     * The song's lyrics and the Lyrics module's controls: in game on or off,
     * where they show, the spot in the world, and the timing offset.
     */
    private void drawLyrics(GuiGraphics g, SpotifyPlayer.State s, int x, int y, int w, int mouseX, int mouseY) {
        dev.breeze.modules.LyricsHud module = dev.breeze.modules.LyricsHud.get();
        Minecraft mc = Minecraft.getInstance();
        int bottom = py + ph - 14;
        if (module != null) {
            int bx = x;
            String onOff = module.isEnabled() ? "In game: On" : "In game: Off";
            bx = lyricsButton(g, bx, y, onOff, mouseX, mouseY, module.isEnabled(), () -> {
                module.setEnabled(!module.isEnabled());
                dev.breeze.config.BreezeConfig.save();
            });
            if (module.worldModes()) {
                bx = lyricsButton(g, bx, y, "Show: " + module.where(), mouseX, mouseY, false, () -> {
                    module.cycleWhere();
                    dev.breeze.config.BreezeConfig.save();
                });
            }
            int by = y + 20;
            bx = x;
            bx = lyricsButton(g, bx, by, "-", mouseX, mouseY, false, () -> nudge(module, -250));
            String off = String.format(java.util.Locale.ROOT, "%+.2f s", module.offsetMs() / 1000.0);
            g.drawString(this.font, off, bx, by + 4, Palette.TEXT_SECONDARY, false);
            bx += this.font.width("+0.00 s") + 4;
            lyricsButton(g, bx, by, "+", mouseX, mouseY, false, () -> nudge(module, 250));
            y += 42;
            // The spot in the world, on a row of its own: a small window has no room beside the offset.
            if (dev.breeze.modules.LyricsHud.IN_WORLD.equals(module.where()) && mc.level != null) {
                boolean placed = dev.breeze.music.LyricsPlace.placedHere(mc);
                bx = lyricsButton(g, x, y - 2, placed ? "Move here" : "Place here", mouseX, mouseY, false,
                        () -> dev.breeze.music.LyricsPlace.placeAtLook(mc));
                if (placed) lyricsButton(g, bx, y - 2, "Remove", mouseX, mouseY, false, () -> dev.breeze.music.LyricsPlace.removeHere(mc));
                y += 20;
            }
        }
        SpotifyPlayer.Track t = s.track;
        if (t == null) {
            for (String line : wrap("Play a song to see its lyrics.", w)) {
                g.drawString(this.font, line, x, y, Palette.TEXT_FAINT, false);
                y += 11;
            }
            return;
        }
        dev.breeze.music.Lyrics.Now now = dev.breeze.music.Lyrics.now(s, System.currentTimeMillis(), module == null ? 0 : module.offsetMs());
        dev.breeze.music.Lyrics.Result r = now.result;
        String note = switch (r.status) {
            case LOADING -> "Looking for lyrics";
            case NOT_FOUND -> "No lyrics found for this song.";
            case INSTRUMENTAL -> "This one has no words.";
            case FAILED -> "Could not reach the lyrics service. Trying again in a minute.";
            default -> null;
        };
        if (note != null) {
            for (String line : wrap(note, w)) {
                g.drawString(this.font, line, x, y, Palette.TEXT_FAINT, false);
                y += 11;
            }
            if (r.status == dev.breeze.music.Lyrics.Status.NOT_FOUND || r.status == dev.breeze.music.Lyrics.Status.FAILED) {
                lyricsButton(g, x, y + 4, "Look again", mouseX, mouseY, false,
                        () -> dev.breeze.music.Lyrics.forget(t.title, t.artist, t.durationMs));
            }
            return;
        }
        if (now.estimated && r.status == dev.breeze.music.Lyrics.Status.SYNCED) {
            for (String line : wrap("This player does not tell where the song is, so the timing is a guess. Use - and + to line it up.", w, 3)) {
                g.drawString(this.font, line, x, y, 0xFFFFB454, false);
                y += 11;
            }
            y += 2;
        }
        int rows = Math.max(1, (bottom - y) / 11);
        if (r.status == dev.breeze.music.Lyrics.Status.SYNCED) {
            // The line being sung stays a third of the way down.
            int centre = Math.max(0, now.index);
            int from = Math.max(0, Math.min(centre - rows / 3, r.lines.size() - rows));
            for (int i = 0; i < rows && from + i < r.lines.size(); i++) {
                boolean sung = from + i == now.index;
                String text = r.lines.get(from + i).text;
                g.drawString(this.font, this.font.plainSubstrByWidth(text.isEmpty() ? "\u266a" : text, w), x, y + i * 11,
                        sung ? Palette.TEXT_PRIMARY : Palette.TEXT_FAINT, false);
            }
            return;
        }
        // Without times: the whole text, scrolled with the wheel.
        lyricsScroll = Math.max(0, Math.min(lyricsScroll, Math.max(0, r.plain.size() - rows)));
        for (int i = 0; i < rows && lyricsScroll + i < r.plain.size(); i++) {
            g.drawString(this.font, this.font.plainSubstrByWidth(r.plain.get(lyricsScroll + i), w), x, y + i * 11, Palette.TEXT_SECONDARY, false);
        }
    }

    /** A button of the Lyrics tab: drawn, remembered for clicks; the x after it. */
    private int lyricsButton(GuiGraphics g, int x, int y, String label, int mouseX, int mouseY, boolean on, Runnable run) {
        int w = this.font.width(label) + 10;
        button(g, x, y, w, label, mouseX, mouseY, on);
        hits.add(new Hit(x, y, w, 16, run));
        return x + w + 4;
    }

    private static void nudge(dev.breeze.modules.LyricsHud module, int byMs) {
        module.nudgeOffset(byMs);
        dev.breeze.config.BreezeConfig.save();
    }

    private void setSource(boolean thisComputer) {
        dev.breeze.modules.NowPlayingHud hud = dev.breeze.modules.NowPlayingHud.get();
        if (hud != null) {
            hud.preferLocal(thisComputer);
            dev.breeze.config.BreezeConfig.save();
        }
        SpotifyPlayer.preferLocal(thisComputer);
        listing = new SpotifyPlayer.Listing(List.of(), null, null);
    }

    private void drawInput(GuiGraphics g, int x, int y, int w, String text, boolean focused, String placeholder) {
        Glass.fillRounded(g, x, y, x + w, y + 16, Glass.RADIUS_SM, Palette.BG);
        Glass.roundedBorder(g, x, y, x + w, y + 16, Glass.RADIUS_SM, focused ? Theme.primary() : Palette.BORDER);
        boolean empty = text.isEmpty();
        String shown = empty && !focused ? placeholder : text + (focused && (System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "");
        g.drawString(this.font, this.font.plainSubstrByWidth(shown, w - 10), x + 5, y + 4,
                empty && !focused ? Palette.TEXT_FAINT : Palette.TEXT_PRIMARY, false);
    }

    private void button(GuiGraphics g, int x, int y, int w, String label, int mouseX, int mouseY, boolean on) {
        button(g, x, y, w, label, mouseX, mouseY, on, true);
    }

    private void button(GuiGraphics g, int x, int y, int w, String label, int mouseX, int mouseY, boolean on, boolean enabled) {
        boolean hover = enabled && inside(mouseX, mouseY, x, y, w, 16);
        int fill = on ? Theme.withAlpha(Theme.primary(), 0x55) : hover ? Theme.cardHover() : Theme.cardBg();
        Glass.fillRounded(g, x, y, x + w, y + 16, Glass.RADIUS_SM, fill);
        Glass.roundedBorder(g, x, y, x + w, y + 16, Glass.RADIUS_SM, hover ? Palette.BORDER_HOVER : Palette.BORDER);
        g.drawString(this.font, label, x + (w - this.font.width(label)) / 2, y + 4,
                enabled ? Palette.TEXT_PRIMARY : Palette.TEXT_FAINT, false);
    }

    private List<String> wrap(String text, int width) {
        return wrap(text, width, 8);
    }

    /** Word-wrapped to width, at most maxLines (the last one cut short). */
    private List<String> wrap(String text, int width, int maxLines) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = line.length() == 0 ? word : line + " " + word;
            if (this.font.width(next) <= width) {
                line.setLength(0);
                line.append(next);
                continue;
            }
            if (line.length() > 0) out.add(line.toString());
            line.setLength(0);
            line.append(this.font.plainSubstrByWidth(word, width));
            if (out.size() == maxLines) break;
        }
        if (line.length() > 0 && out.size() < maxLines) out.add(line.toString());
        if (out.size() > maxLines) return out.subList(0, maxLines);
        return out;
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    // ── input ───────────────────────────────────────────────────────────────

    @Override
    protected boolean onMouseClicked(double mx, double my, int button) {
        if (button != 0) return true;
        // The header's switch and the Lyrics tab's buttons, as drawn in the last frame.
        for (Hit h : new ArrayList<>(hits)) {
            if (inside(mx, my, h.x(), h.y(), h.w(), h.h())) {
                h.run().run();
                return true;
            }
        }
        SpotifyPlayer.State s = SpotifyPlayer.state();
        int x = lx(), w = lw();
        String problem = SpotifyPlayer.explain(s);
        if (problem != null) {
            int ty = py + 28 + wrap(problem, w).size() * 11 + 6;
            if (s.phase == SpotifyPlayer.Phase.NOT_CONNECTED) {
                if (inside(mx, my, x, ty, 110, 16)) {
                    connect();
                    return true;
                }
                ty += 24 + (connectProblem != null ? wrap(connectProblem, w).size() * 11 : 0);
            }
            if (inside(mx, my, x, ty, 80, 16)) SpotifyPlayer.recheck();
            return true;
        }

        // Seek.
        if (s.track != null && s.full() && s.canControl() && inside(mx, my, x, seekY() - 3, w, 9)) {
            dragSeek = fraction(mx, x, w);
            return true;
        }
        // Transport.
        if (s.canControl()) {
            int[][] c = controls(s);
            for (int i = 0; i < c.length; i++) {
                if (!inside(mx, my, c[i][0], controlsY(), c[i][1], 16)) continue;
                if (!s.full() && (i == 0 || i == 4)) return true;
                switch (i) {
                    case 0 -> SpotifyPlayer.shuffle(!s.shuffle);
                    case 1 -> SpotifyPlayer.previous();
                    case 2 -> SpotifyPlayer.togglePlay();
                    case 3 -> SpotifyPlayer.next();
                    default -> SpotifyPlayer.cycleRepeat();
                }
                return true;
            }
            int vx = x + 44, vw = w - 44 - 30;
            if (s.volumeSupported && inside(mx, my, vx - 2, volumeY() - 1, vw + 4, 10)) {
                dragVolume = (int) Math.round(fraction(mx, vx, vw) * 100);
                return true;
            }
        }

        int cy = localConnectY(s);
        if (cy >= 0 && inside(mx, my, x, cy, 110, 16)) {
            connect();
            return true;
        }

        // Right column.
        if (s.phase != SpotifyPlayer.Phase.READY) return true;
        int[][] tabs = tabs();
        for (Tab t : Tab.values()) {
            if (inside(mx, my, tabs[t.ordinal()][0], py + 28, tabs[t.ordinal()][1], 16)) {
                openTab(t);
                return true;
            }
        }
        if (tab == Tab.LYRICS) return true;
        typing = tab == Tab.SEARCH && inside(mx, my, rx(), py + 48, rw(), 16);
        SpotifyPlayer.Listing l = listing;
        if (l.problem != null && "RECONNECT_REQUIRED".equals(l.reason)) {
            connect();
            return true;
        }
        int top = listTop();
        for (int i = 0; i < visibleRows() && scroll + i < l.items.size(); i++) {
            if (!inside(mx, my, rx() - 2, top + i * ROW, rw() + 4, ROW - 2)) continue;
            SpotifyPlayer.Item it = l.items.get(scroll + i);
            switch (tab) {
                case SEARCH -> SpotifyPlayer.play(it.uri, null);
                case PLAYLISTS -> SpotifyPlayer.play(null, it.uri);
                case DEVICES -> {
                    if (it.id != null) SpotifyPlayer.transfer(it.id);
                }
                default -> { }
            }
            return true;
        }
        return true;
    }

    @Override
    protected boolean onMouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragSeek >= 0) dragSeek = fraction(mx, lx(), lw());
        if (dragVolume >= 0) {
            int vx = lx() + 44, vw = lw() - 44 - 30;
            dragVolume = (int) Math.round(fraction(mx, vx, vw) * 100);
        }
        return true;
    }

    @Override
    protected boolean onMouseReleased(double mx, double my, int button) {
        SpotifyPlayer.State s = SpotifyPlayer.state();
        if (dragSeek >= 0) {
            if (s.track != null) SpotifyPlayer.seek((long) (dragSeek * s.track.durationMs));
            dragSeek = -1;
        }
        if (dragVolume >= 0) {
            SpotifyPlayer.volume(dragVolume);
            dragVolume = -1;
        }
        return true;
    }

    @Override
    protected boolean onMouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (scrollY != 0) {
            scroll = Math.max(0, scroll - (int) Math.signum(scrollY));
            lyricsScroll = Math.max(0, lyricsScroll - 2 * (int) Math.signum(scrollY));
        }
        return true;
    }

    @Override
    protected boolean onKeyPressed(int key, int scanCode, int modifiers) {
        dev.breeze.devtest.AutoTest.screenKey("music", key, key == InputConstants.KEY_RSHIFT);
        if (key == InputConstants.KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (typing && tab == Tab.SEARCH) {
            if (key == InputConstants.KEY_BACKSPACE) {
                if (!query.isEmpty()) query = query.substring(0, query.length() - 1);
                return true;
            }
            if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
                runSearch();
                return true;
            }
            return true;
        }
        if (key == InputConstants.KEY_SPACE && SpotifyPlayer.state().canControl()) {
            SpotifyPlayer.togglePlay();
            return true;
        }
        return superKeyPressed(key, scanCode, modifiers);
    }

    @Override
    protected boolean onCharTyped(int c, int modifiers) {
        if (typing && tab == Tab.SEARCH && c >= 32 && c != 127 && Character.isBmpCodePoint(c) && query.length() < 80) {
            query += (char) c;
            return true;
        }
        return false;
    }

    private static double fraction(double mx, int x, int w) {
        return Math.max(0, Math.min(1, (mx - x) / Math.max(1, w)));
    }

    private void openTab(Tab t) {
        tab = t;
        scroll = 0;
        typing = t == Tab.SEARCH;
        listing = new SpotifyPlayer.Listing(List.of(), null, null);
        switch (t) {
            case SEARCH -> {
                if (!query.isBlank()) runSearch();
            }
            case PLAYLISTS -> load(SpotifyPlayer.playlists());
            case DEVICES -> load(SpotifyPlayer.devices());
            default -> { }
        }
    }

    private void runSearch() {
        scroll = 0;
        load(SpotifyPlayer.search(query));
    }

    private void load(java.util.concurrent.CompletableFuture<SpotifyPlayer.Listing> f) {
        loading = true;
        f.whenComplete((l, err) -> {
            listing = l != null ? l : new SpotifyPlayer.Listing(List.of(), "Spotify did not answer.", null);
            loading = false;
        });
    }

    private void connect() {
        connectProblem = null;
        SpotifyPlayer.loginUrl().whenComplete((url, err) -> Minecraft.getInstance().execute(() -> {
            if (url == null) {
                connectProblem = "Could not start connecting. Connect Spotify in the Breeze launcher's Breeze FM page instead.";
                return;
            }
            try {
                dev.breeze.compat.Links.open(URI.create(url));
                connectProblem = "Finish in your browser, then press Check again.";
            } catch (Throwable t) {
                connectProblem = "Could not open your browser. Connect Spotify in the Breeze launcher's Breeze FM page instead.";
            }
        }));
    }

    /** The Devices tab, for "Spotify is not open anywhere". */
    @Override
    public void tick() {
        SpotifyPlayer.State s = SpotifyPlayer.state();
        if (s.needsDevice && s.messageAt != devicesShownFor && s.phase == SpotifyPlayer.Phase.READY) {
            devicesShownFor = s.messageAt;
            if (tab != Tab.DEVICES) openTab(Tab.DEVICES);
        }
    }
}
