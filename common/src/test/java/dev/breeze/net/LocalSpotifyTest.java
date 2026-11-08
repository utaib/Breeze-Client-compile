package dev.breeze.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalSpotifyTest {

    @Test
    void macPlayingTrack() {
        LocalSpotify.Reading r = LocalSpotify.parseMac(
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
        LocalSpotify.Reading paused = LocalSpotify.parseMac("paused\nSong\nArtist\nmissing value\nmissing value\n0\n1000");
        assertEquals(Boolean.FALSE, paused.playing);
        assertNull(paused.album);
        assertNull(paused.artworkUrl);
        LocalSpotify.Reading stopped = LocalSpotify.parseMac("stopped\n");
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
        LocalSpotify.Reading r = LocalSpotify.parseLinux(STATUS, META, "   variant       int64 61250000\n");
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
        LocalSpotify.Reading r = LocalSpotify.parseLinux(STATUS, META, "   variant       int64 0\n");
        assertEquals(-1, r.positionMs);
        LocalSpotify.Reading paused = LocalSpotify.parseLinux("   variant       string \"Paused\"\n", META, null);
        assertEquals(Boolean.FALSE, paused.playing);
        assertEquals(-1, paused.positionMs);
    }

    @Test
    void windowsTitles() {
        LocalSpotify.Reading playing = LocalSpotify.parseWindowTitle("The Weeknd - Blinding Lights");
        assertEquals(Boolean.TRUE, playing.playing);
        assertEquals("The Weeknd", playing.artist);
        assertEquals("Blinding Lights", playing.title);
        LocalSpotify.Reading dashes = LocalSpotify.parseWindowTitle("Daft Punk - One More Time - Radio Edit");
        assertEquals("Daft Punk", dashes.artist);
        assertEquals("One More Time - Radio Edit", dashes.title);
        for (String idle : new String[]{"Spotify", "Spotify Premium", "Spotify Free"}) {
            LocalSpotify.Reading r = LocalSpotify.parseWindowTitle(idle);
            assertTrue(r.present, idle);
            assertEquals(Boolean.FALSE, r.playing, idle);
            assertFalse(r.hasTrack(), idle);
        }
        assertFalse(LocalSpotify.parseWindowTitle("").present);
    }

    @Test
    void onlySpotifyImageHosts() {
        assertEquals("https://i.scdn.co/image/x", LocalSpotify.artwork("http://i.scdn.co/image/x"));
        assertNull(LocalSpotify.artwork("https://example.com/image/x"));
        assertNull(LocalSpotify.artwork("file:///etc/passwd"));
    }

    @Test
    void systemNames() {
        assertEquals(LocalSpotify.Os.MAC, LocalSpotify.os("Mac OS X"));
        assertEquals(LocalSpotify.Os.WINDOWS, LocalSpotify.os("Windows 11"));
        assertEquals(LocalSpotify.Os.LINUX, LocalSpotify.os("Linux"));
        assertEquals(LocalSpotify.Os.OTHER, LocalSpotify.os("SunOS"));
    }
}
