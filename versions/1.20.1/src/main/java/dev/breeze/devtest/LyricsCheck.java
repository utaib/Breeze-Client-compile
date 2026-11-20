package dev.breeze.devtest;

import dev.breeze.modules.LyricsHud;
import dev.breeze.music.LyricsPlace;
import dev.breeze.net.SpotifyPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Self-test of the Lyrics module (2.26.0), in the test world. The song comes
 * from whatever the Music player has (the stand-in browser, or the stand-in
 * Spotify account where there is no session bus), the words from the
 * stand-in lyrics service, whose every line names its own second ("Breeze
 * lyric 42" at 0:42).
 *
 * On screen: the line shown must be the one for the song's position. In the
 * world: the same, placed four blocks ahead, and its own pixels (drawn in
 * magenta, which nothing else on the test screen is) must be in the
 * screenshot Minecraft saved: a draw call is not proof of a picture. The
 * spot must come back from the saved file. Where this Minecraft cannot draw
 * in the world (1.21.9), only the on-screen part runs.
 */
final class LyricsCheck {

    private LyricsCheck() {}

    private static final Pattern LINE = Pattern.compile("Breeze lyric (\\d+)");

    private static int step;
    private static long stepAt;
    private static boolean wasOn;
    private static String hudLine = "", worldLine = "";
    private static long hudPosition = -1, worldPosition = -1;
    private static final StringBuilder notes = new StringBuilder();
    private static long lastToggle;

    /** False when the check cannot run here (logged as skipped). */
    static boolean start(Minecraft mc) {
        LyricsHud m = LyricsHud.get();
        if (m == null || mc.player == null || System.getProperty("breeze.lyrics.url") == null) {
            AutoTest.report("lyrics-check", "pass", "true", "skipped", "no Lyrics module, player or stand-in lyrics service");
            return false;
        }
        wasOn = m.isEnabled();
        step = 0;
        stepAt = System.currentTimeMillis();
        notes.setLength(0);
        return true;
    }

    private static long position() {
        SpotifyPlayer.State s = SpotifyPlayer.state();
        return s.track != null && s.positionKnown() ? s.progressNow(System.currentTimeMillis()) : -1;
    }

    /** Whether a shown line belongs to a position: the stand-in's lines are two seconds apart. */
    private static boolean fits(String line, long positionMs) {
        Matcher m = LINE.matcher(line == null ? "" : line);
        if (!m.find() || positionMs < 0) return false;
        long second = Long.parseLong(m.group(1));
        return Math.abs(second * 1000 - positionMs) <= 4_500;
    }

    /** True when the check is over. */
    static boolean step(Minecraft mc, long age) {
        LyricsHud m = LyricsHud.get();
        long now = System.currentTimeMillis();
        long since = now - stepAt;
        SpotifyPlayer.tick(now, 1_000);
        SpotifyPlayer.State s = SpotifyPlayer.state();
        switch (step) {
            case 0 -> {
                // After the local music check, which pauses the stand-in player: play it again.
                if (!LocalMediaCheck.finished() && since < 60_000) return false;
                if (s.track == null && since < 30_000) return false;
                if (s.track != null && !s.playing && now - lastToggle > 5_000) {
                    lastToggle = now;
                    SpotifyPlayer.togglePlay();
                }
                if ((s.track == null || !s.playing) && since < 40_000) return false;
                if (s.track == null || !s.playing) return done(mc, false, "no song playing to show lyrics for (phase " + s.phase + ")");
                AutoTest.setMode("Lyrics", "where", LyricsHud.ON_SCREEN);
                AutoTest.setInt("Lyrics", "offset", 0);
                if (!m.isEnabled()) m.setEnabled(true);
                next(1);
            }
            case 1 -> {
                // The lookup, then a frame with the line.
                String line = LyricsHud.lastHud();
                if (!fits(line, position()) && since < 30_000) return false;
                hudLine = line;
                hudPosition = position();
                if (!fits(hudLine, hudPosition)) return done(mc, false, "on screen: \"" + hudLine + "\" at " + hudPosition + " ms");
                if (!m.worldModes()) return done(mc, true, "on screen only: no world drawing on this version");
                // Four blocks ahead at eye level, as the TNT label is checked.
                Vec3 ahead = mc.player.getViewVector(1f);
                double len = Math.max(0.01, Math.hypot(ahead.x, ahead.z));
                Vec3 spot = new Vec3(mc.player.getX() + ahead.x / len * 4, mc.player.getEyeY() + 0.4, mc.player.getZ() + ahead.z / len * 4);
                LyricsPlace.place(mc, spot);
                Vec3 back = LyricsPlace.get(mc);
                if (back == null || back.distanceTo(spot) > 0.02) return done(mc, false, "the spot was not kept");
                AutoTest.setMode("Lyrics", "where", LyricsHud.IN_WORLD);
                AutoTest.setBool("Lyrics", "through", true);
                LyricsHud.testColour(0xFFFF00FF);
                next(2);
            }
            case 2 -> {
                if (since < 1_500) return false;
                String line = LyricsHud.lastWorld();
                if (!fits(line, position()) && since < 15_000) return false;
                worldLine = line;
                worldPosition = position();
                AutoTest.shot(mc, "autotest-lyrics");
                next(3);
            }
            default -> {
                // Minecraft writes the screenshot on another thread: wait for a whole file.
                java.nio.file.Path png = mc.gameDirectory.toPath().resolve("screenshots").resolve("autotest-lyrics.png");
                int magenta;
                try {
                    magenta = dev.breeze.render.PngPixels.count(Files.readAllBytes(png), 0xFF00FF, 24);
                } catch (Throwable t) {
                    if (since < 8_000) return false;
                    return done(mc, false, "the screenshot was not read: " + t);
                }
                notes.append("linePixels ").append(magenta);
                boolean ok = fits(worldLine, worldPosition) && magenta >= 20;
                return done(mc, ok, ok ? "" : "in the world: \"" + worldLine + "\" at " + worldPosition + " ms, " + magenta + " pixels");
            }
        }
        return false;
    }

    private static void next(int to) {
        step = to;
        stepAt = System.currentTimeMillis();
    }

    private static boolean done(Minecraft mc, boolean pass, String why) {
        LyricsHud m = LyricsHud.get();
        LyricsHud.testColour(0);
        SpotifyPlayer.State s = SpotifyPlayer.state();
        AutoTest.report("lyrics-check", "pass", String.valueOf(pass), "onScreen", hudLine, "onScreenAtMs", String.valueOf(hudPosition),
                "inWorld", worldLine, "inWorldAtMs", String.valueOf(worldPosition), "source", String.valueOf(s.phase),
                "song", s.track == null ? "" : s.track.title, "notes", notes.toString(), "reason", why);
        try {
            LyricsPlace.removeHere(mc);
            AutoTest.setMode("Lyrics", "where", LyricsHud.ON_SCREEN);
            AutoTest.setBool("Lyrics", "through", false);
            if (m != null && m.isEnabled() != wasOn) m.setEnabled(wasOn);
        } catch (Throwable ignored) {
        }
        return true;
    }
}
