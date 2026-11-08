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
 * The Music player: the player's Spotify, controlled from inside the game.
 *
 * Opened with M (Controls, under Breeze) or from the Breeze menus. Left: what
 * is playing, a seek bar, play, pause, skip, shuffle, repeat and volume.
 * Right: search Spotify, the account's playlists, and its devices (Spotify
 * plays on one of them: this computer's Spotify app, a phone, a speaker).
 * A native screen on every version, so it opens at once, in a world or not,
 * without the embedded browser.
 *
 * It does not pause singleplayer: changing the song is not a reason to stop
 * the world.
 */
public final class MusicScreen extends BreezeScreen {

    private static final int GREEN = 0xFF1ED760;
    private static final int ROW = 22;

    private enum Tab { SEARCH, PLAYLISTS, DEVICES }

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

        g.drawString(this.font, "Music", px + 14, py + 10, Palette.TEXT_PRIMARY, false);
        String who = s.displayName != null ? "Spotify, " + s.displayName : "Spotify";
        g.drawString(this.font, this.font.plainSubstrByWidth(who, pw / 2), px + 14 + this.font.width("Music") + 8, py + 10, GREEN, false);
        g.drawString(this.font, "Esc to close", px + pw - 12 - this.font.width("Esc to close"), py + 10, Palette.TEXT_FAINT, false);

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
            for (String line : wrap("Play something below, or open Spotify on any device.", tw)) {
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
            g.drawString(this.font, this.font.plainSubstrByWidth("on " + s.deviceName, tw), tx, y + art - 9, Palette.TEXT_FAINT, false);
        }

        // Seek bar and times.
        long now = System.currentTimeMillis();
        long duration = t == null ? 0 : t.durationMs;
        long position = dragSeek >= 0 ? (long) (dragSeek * duration) : s.progressNow(now);
        int by = seekY();
        boolean overSeek = t != null && inside(mouseX, mouseY, x, by - 3, w, 9);
        g.fill(x, by, x + w, by + 3, Palette.alpha(0xFFFFFFFF, 0x33));
        if (duration > 0) {
            int filled = (int) Math.round(w * Math.min(1.0, position / (double) duration));
            g.fill(x, by, x + filled, by + 3, s.playing ? GREEN : 0xFFB0B0B0);
            if (overSeek || dragSeek >= 0) g.fill(x + filled - 1, by - 2, x + filled + 2, by + 5, Palette.TEXT_PRIMARY);
        }
        g.drawString(this.font, SpotifyPlayer.time(position), x, by + 6, Palette.TEXT_FAINT, false);
        String end = SpotifyPlayer.time(duration);
        g.drawString(this.font, end, x + w - this.font.width(end), by + 6, Palette.TEXT_FAINT, false);

        // Transport.
        boolean can = s.canControl();
        int[][] c = controls(s);
        String[] labels = labels(s);
        for (int i = 0; i < c.length; i++) {
            boolean on = (i == 0 && s.shuffle) || (i == 4 && !"off".equals(s.repeat));
            button(g, c[i][0], controlsY(), c[i][1], labels[i], mouseX, mouseY, on, can);
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

    private void drawRight(GuiGraphics g, SpotifyPlayer.State s, int mouseX, int mouseY) {
        int x = rx(), y = py + 28, w = rw();
        Glass.fillRounded(g, x - 4, y - 4, x + w + 4, py + ph - 8, Glass.RADIUS_SM, Palette.alpha(Palette.BG_DEEP, 0x33));
        if (s.phase != SpotifyPlayer.Phase.READY) {
            g.drawString(this.font, this.font.plainSubstrByWidth("Your music appears here once Spotify is connected.", w), x, y, Palette.TEXT_FAINT, false);
            return;
        }
        int tabX = x;
        for (Tab t : Tab.values()) {
            String label = switch (t) {
                case SEARCH -> "Search";
                case PLAYLISTS -> "Playlists";
                case DEVICES -> "Devices";
            };
            int tw = this.font.width(label) + 12;
            button(g, tabX, y, tw, label, mouseX, mouseY, tab == t);
            tabX += tw + 4;
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
        if (s.track != null && s.canControl() && inside(mx, my, x, seekY() - 3, w, 9)) {
            dragSeek = fraction(mx, x, w);
            return true;
        }
        // Transport.
        if (s.canControl()) {
            int[][] c = controls(s);
            for (int i = 0; i < c.length; i++) {
                if (!inside(mx, my, c[i][0], controlsY(), c[i][1], 16)) continue;
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

        // Right column.
        if (s.phase != SpotifyPlayer.Phase.READY) return true;
        int tabX = rx();
        for (Tab t : Tab.values()) {
            String label = switch (t) {
                case SEARCH -> "Search";
                case PLAYLISTS -> "Playlists";
                case DEVICES -> "Devices";
            };
            int tw = this.font.width(label) + 12;
            if (inside(mx, my, tabX, py + 28, tw, 16)) {
                openTab(t);
                return true;
            }
            tabX += tw + 4;
        }
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
        if (scrollY != 0) scroll = Math.max(0, scroll - (int) Math.signum(scrollY));
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
