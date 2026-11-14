package dev.breeze.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalMediaTest {


    @Test
    void macPlayingTrack() {
        LocalMedia.Reading r = LocalMedia.parseMac(
                "playing\nBlinding Lights\nThe Weeknd\nAfter Hours\nhttps://i.scdn.co/image/ab67616d0000b273abc\n61250\n200040\n");
        assertTrue(r.present);
        assertEquals(Boolean.TRUE, r.playing);
        assertEquals("Blinding Lights", r.title);
        assertEquals("The Weeknd", r.artist);
        assertEquals("After Hours", r.album);
        assertEquals("https://i.scdn.co/image/ab67616d0000b273abc", r.artworkUrl);
        assertEquals(61250, r.positionMs);
        assertEquals(200040, r.durationMs);
    }

    @Test
    void macPausedAndStopped() {
        LocalMedia.Reading paused = LocalMedia.parseMac("paused\nSong\nArtist\nmissing value\nmissing value\n0\n1000");
        assertEquals(Boolean.FALSE, paused.playing);
        assertNull(paused.album);
        assertNull(paused.artworkUrl);
        LocalMedia.Reading stopped = LocalMedia.parseMac("stopped\n");
        assertTrue(stopped.present);
        assertFalse(stopped.hasTrack());
    }

    private static final String STATUS = "method return time=1.2 sender=:1.89 -> destination=:1.123 serial=7 reply_serial=2\n"
            + "   variant       string \"Playing\"\n";

    private static final String META = "method return time=1.2 sender=:1.89 -> destination=:1.123 serial=8 reply_serial=2\n"
            + "   variant       array [\n"
            + "         dict entry(\n"
            + "            string \"mpris:trackid\"\n"
            + "            variant                string \"/com/spotify/track/0VjIjW4GlUZAMYd2vXMi3b\"\n"
            + "         )\n"
            + "         dict entry(\n"
            + "            string \"mpris:length\"\n"
            + "            variant                uint64 200040000\n"
            + "         )\n"
            + "         dict entry(\n"
            + "            string \"mpris:artUrl\"\n"
            + "            variant                string \"https://open.spotify.com/image/ab67616d0000b273abc\"\n"
            + "         )\n"
            + "         dict entry(\n"
            + "            string \"xesam:album\"\n"
            + "            variant                string \"After Hours\"\n"
            + "         )\n"
            + "         dict entry(\n"
            + "            string \"xesam:artist\"\n"
            + "            variant                array [\n"
            + "                  string \"The Weeknd\"\n"
            + "                  string \"Daft Punk\"\n"
            + "               ]\n"
            + "         )\n"
            + "         dict entry(\n"
            + "            string \"xesam:title\"\n"
            + "            variant                string \"Starboy - Remix\"\n"
            + "         )\n"
            + "      ]\n";

    @Test
    void linuxMprisReading() {
        LocalMedia.Reading r = LocalMedia.parseLinux(STATUS, META, "   variant       int64 61250000\n", true);
        assertTrue(r.present);
        assertEquals(Boolean.TRUE, r.playing);
        assertEquals("Starboy - Remix", r.title);
        assertEquals("The Weeknd, Daft Punk", r.artist);
        assertEquals("After Hours", r.album);
        assertEquals("https://i.scdn.co/image/ab67616d0000b273abc", r.artworkUrl);
        assertEquals(200040, r.durationMs);
        assertEquals(61250, r.positionMs);
    }

    @Test
    void linuxPositionZeroWhilePlayingIsUnknown() {
        LocalMedia.Reading r = LocalMedia.parseLinux(STATUS, META, "   variant       int64 0\n", true);
        assertEquals(-1, r.positionMs);
        // Any other player at the very start of a song really is at 0.
        assertEquals(0, LocalMedia.parseLinux(STATUS, META, "   variant       int64 0\n", false).positionMs);
        LocalMedia.Reading paused = LocalMedia.parseLinux("   variant       string \"Paused\"\n", META, null, true);
        assertEquals(Boolean.FALSE, paused.playing);
        assertEquals(-1, paused.positionMs);
    }

    @Test
    void windowsTitles() {
        LocalMedia.Reading playing = LocalMedia.parseWindowTitle("The Weeknd - Blinding Lights");
        assertEquals(Boolean.TRUE, playing.playing);
        assertEquals("The Weeknd", playing.artist);
        assertEquals("Blinding Lights", playing.title);
        LocalMedia.Reading dashes = LocalMedia.parseWindowTitle("Daft Punk - One More Time - Radio Edit");
        assertEquals("Daft Punk", dashes.artist);
        assertEquals("One More Time - Radio Edit", dashes.title);
        for (String idle : new String[]{"Spotify", "Spotify Premium", "Spotify Free"}) {
            LocalMedia.Reading r = LocalMedia.parseWindowTitle(idle);
            assertTrue(r.present, idle);
            assertEquals(Boolean.FALSE, r.playing, idle);
            assertFalse(r.hasTrack(), idle);
        }
        assertFalse(LocalMedia.parseWindowTitle("").present);
    }

    @Test
    void coversOnlyFromKnownHostsOrALocalFile() {
        assertEquals("https://i.scdn.co/image/x", LocalMedia.artwork("http://i.scdn.co/image/x"));
        assertEquals("https://lh3.googleusercontent.com/abc=w544", LocalMedia.artwork("https://lh3.googleusercontent.com/abc=w544"));
        assertNull(LocalMedia.artwork("https://example.com/image/x"));
        assertNull(LocalMedia.artwork("http://lh3.googleusercontent.com/abc"));
        assertNull(LocalMedia.artwork("https://i.scdn.co.evil.example/image/x"));
        // A browser writes the cover to a file; it is kept as a key, not fetched as an address.
        assertEquals("local:file:/tmp/cover.png", LocalMedia.artwork("file:///tmp/cover.png").replace('\\', '/').replaceFirst("file:[A-Za-z]:", "file:"));
        assertNull(LocalMedia.artwork("file://server/share/x.png"));
        assertNull(LocalMedia.artworkBytes("local:file:/no/such/file.png"));
        assertNull(LocalMedia.artworkBytes("https://i.scdn.co/image/x"));
    }

    @Test
    void aLocalCoverFileIsReadAndALargeOneIsNot(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path small = dir.resolve("cover.png");
        java.nio.file.Files.write(small, new byte[]{1, 2, 3});
        String key = LocalMedia.artwork(small.toUri().toString());
        assertTrue(key.startsWith("local:file:"), key);
        assertEquals(3, LocalMedia.artworkBytes(key).length);
        java.nio.file.Path large = dir.resolve("large.png");
        java.nio.file.Files.write(large, new byte[LocalMedia.MAX_ART_BYTES + 1]);
        assertNull(LocalMedia.artworkBytes(LocalMedia.artwork(large.toUri().toString())));
    }

    @Test
    void everyMprisPlayerIsListedButNotTheRepeater() {
        String out = "method return time=1.2 sender=org.freedesktop.DBus -> destination=:1.9 serial=3 reply_serial=2\n"
                + "   array [\n"
                + "      string \"org.freedesktop.DBus\"\n"
                + "      string \":1.5\"\n"
                + "      string \"org.mpris.MediaPlayer2.spotify\"\n"
                + "      string \"org.mpris.MediaPlayer2.chromium.instance4242\"\n"
                + "      string \"org.mpris.MediaPlayer2.playerctld\"\n"
                + "      string \"org.mpris.MediaPlayer2.bad name;rm\"\n"
                + "   ]\n";
        assertEquals(java.util.List.of("org.mpris.MediaPlayer2.spotify", "org.mpris.MediaPlayer2.chromium.instance4242"),
                LocalMedia.parseNames(out));
        assertEquals(java.util.List.of(), LocalMedia.parseNames(null));
    }

    @Test
    void thePlayingPlayerWinsAndTheLastOneIsKept() {
        java.util.List<String> names = java.util.List.of("a", "b", "c");
        assertNull(LocalMedia.pick(java.util.List.of(), java.util.Map.of(), "a"));
        // Nothing plays: the one used last, else the first.
        assertEquals("a", LocalMedia.pick(names, java.util.Map.of(), null));
        assertEquals("b", LocalMedia.pick(names, java.util.Map.of("b", false), "b"));
        // One plays: that one, even if another was used last.
        assertEquals("c", LocalMedia.pick(names, java.util.Map.of("b", false, "c", true), "b"));
        // Two play: the one used last stays.
        assertEquals("c", LocalMedia.pick(names, java.util.Map.of("a", true, "c", true), "c"));
        assertEquals("a", LocalMedia.pick(names, java.util.Map.of("a", true, "c", true), "gone"));
    }

    @Test
    void playerNames() {
        assertEquals("YouTube Music", LocalMedia.appName("YouTube Music", "org.mpris.MediaPlayer2.youtube-music"));
        assertEquals("Chromium", LocalMedia.appName("", "org.mpris.MediaPlayer2.chromium.instance4242"));
        assertEquals("Spotify", LocalMedia.appName(null, "org.mpris.MediaPlayer2.spotify"));
        assertEquals("VLC", LocalMedia.appName(null, "org.mpris.MediaPlayer2.vlc"));
        assertNull(LocalMedia.appName(null, "something.else"));
    }

    @Test
    void textFromAPlayerIsMadeSafeToDraw() {
        assertEquals("Song Name", LocalMedia.clean("  Song\n\u00a7cName\t ", 200));
        assertEquals("abc", LocalMedia.clean("abcdef", 3));
        LocalMedia.Reading r = new LocalMedia.Reading(true, true, "A\u00a7kB", "x\ny", null, null, -1, -1, "Chrome\u0007");
        assertEquals("AB", r.title);
        assertEquals("x y", r.artist);
        assertEquals("Chrome", r.app);
    }

    private static String session(String status, long position, long duration, long updated, long now, boolean art) {
        return "{\"present\":true,\"app\":\"Chrome\",\"status\":\"" + status + "\",\"title\":\"Caf\\u00e9 Song\",\"artist\":\"The Band\","
                + "\"album\":\"\",\"positionMs\":" + position + ",\"durationMs\":" + duration + ",\"updated\":" + updated
                + ",\"now\":" + now + ",\"art\":" + art + "}";
    }

    @Test
    void windowsSessionReading() {
        LocalMedia.Reading r = LocalMediaWindows.parseSession(session("Playing", 30_000, 200_000, 1_000_000, 1_012_000, true));
        assertTrue(r.present);
        assertEquals(Boolean.TRUE, r.playing);
        assertEquals("Caf\u00e9 Song", r.title);
        assertEquals("The Band", r.artist);
        assertNull(r.album);
        assertEquals("Chrome", r.app);
        // Twelve seconds have passed since Windows was told the position.
        assertEquals(42_000, r.positionMs);
        assertEquals(200_000, r.durationMs);
        assertTrue(r.artworkUrl.startsWith("local:smtc:"), r.artworkUrl);
        // Paused: the position stands still.
        assertEquals(30_000, LocalMediaWindows.parseSession(session("Paused", 30_000, 200_000, 1_000_000, 1_012_000, false)).positionMs);
        assertNull(LocalMediaWindows.parseSession(session("Paused", 30_000, 200_000, 1_000_000, 1_012_000, false)).artworkUrl);
    }

    @Test
    void windowsSessionWithoutATimeline() {
        // No length told: neither position nor length is known.
        LocalMedia.Reading none = LocalMediaWindows.parseSession(session("Playing", 0, 0, -11644473600000L, 1_000, false));
        assertEquals(-1, none.positionMs);
        assertEquals(-1, none.durationMs);
        // A player that never updates its position would run past the end.
        LocalMedia.Reading stale = LocalMediaWindows.parseSession(session("Playing", 10_000, 200_000, 1_000_000, 1_900_000, false));
        assertEquals(-1, stale.positionMs);
        assertEquals(200_000, stale.durationMs);
        assertFalse(LocalMediaWindows.parseSession("{\"present\":false}").present);
    }

    @Test
    void windowsPlayerNames() {
        assertEquals("Spotify", LocalMediaWindows.appName("Spotify.exe"));
        assertEquals("Spotify", LocalMediaWindows.appName("SpotifyAB.SpotifyMusic_zpdnekdrzrea0!Spotify"));
        assertEquals("Chrome", LocalMediaWindows.appName("Chrome"));
        assertEquals("Edge", LocalMediaWindows.appName("MSEdge"));
        assertEquals("Firefox", LocalMediaWindows.appName("308046B0AF4A39CB"));
        assertEquals("Apple Music", LocalMediaWindows.appName("AppleInc.AppleMusicWin_nzyj5cx40ttqa!App"));
        assertEquals("Vlc", LocalMediaWindows.appName("vlc.exe"));
        assertNull(LocalMediaWindows.appName(""));
        assertNull(LocalMediaWindows.appName("weird<>name"));
    }

    @Test
    void theHelperScriptIsOneQuoteFreeArgument() {
        // A double quote or a line break would split the command line; a
        // trailing backslash would swallow its closing quote.
        assertFalse(LocalMediaWindows.SCRIPT.contains("\""));
        assertFalse(LocalMediaWindows.SCRIPT.contains("\n"));
        assertFalse(LocalMediaWindows.SCRIPT.endsWith("\\"));
        assertTrue(LocalMediaWindows.SCRIPT.length() < 16_000, "Windows allows 32,767 characters for a command line");
    }

    @Test
    void systemNames() {
        assertEquals(LocalMedia.Os.MAC, LocalMedia.os("Mac OS X"));
        assertEquals(LocalMedia.Os.WINDOWS, LocalMedia.os("Windows 11"));
        assertEquals(LocalMedia.Os.LINUX, LocalMedia.os("Linux"));
        assertEquals(LocalMedia.Os.OTHER, LocalMedia.os("SunOS"));
    }
}
