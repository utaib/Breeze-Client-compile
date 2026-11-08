package dev.breeze.net;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpotifyPlayerTest {

    private static JsonObject json(String s) {
        return new Gson().fromJson(s, JsonObject.class);
    }

    @Test
    void anAccountSpotifyRefusesIsBlockedNotFree() {
        // What the API answers for an account outside the Spotify app's
        // Development Mode allowlist: connected, premium unknown.
        SpotifyPlayer.State s = SpotifyPlayer.applyStatus(SpotifyPlayer.State.EMPTY, json(
                "{\"configured\":true,\"connected\":true,\"premium\":null,\"blocked\":\"NOT_REGISTERED\"}"));
        assertEquals(SpotifyPlayer.Phase.BLOCKED, s.phase);
        assertNull(s.premium, "unknown, never false");
        assertFalse(s.canControl());
        assertTrue(SpotifyPlayer.explain(s).contains("test mode"));
    }

    @Test
    void unknownPremiumStillOffersControls() {
        SpotifyPlayer.State s = SpotifyPlayer.applyStatus(SpotifyPlayer.State.EMPTY, json(
                "{\"configured\":true,\"connected\":true,\"premium\":null}"));
        assertEquals(SpotifyPlayer.Phase.READY, s.phase);
        assertTrue(s.canControl(), "Spotify decides when a command is sent");
        SpotifyPlayer.State free = SpotifyPlayer.applyStatus(SpotifyPlayer.State.EMPTY, json(
                "{\"configured\":true,\"connected\":true,\"premium\":false}"));
        assertFalse(free.canControl());
    }

    @Test
    void notConnectedAndNotConfiguredAreTheirOwnPhases() {
        assertEquals(SpotifyPlayer.Phase.NOT_CONNECTED, SpotifyPlayer.applyStatus(SpotifyPlayer.State.EMPTY,
                json("{\"configured\":true,\"connected\":false}")).phase);
        assertEquals(SpotifyPlayer.Phase.NOT_CONFIGURED, SpotifyPlayer.applyStatus(SpotifyPlayer.State.EMPTY,
                json("{\"configured\":false,\"connected\":false}")).phase);
    }

    @Test
    void nowPlayingIsReadAndProgressMovesOnWhilePlaying() {
        SpotifyPlayer.State s = SpotifyPlayer.applyStatus(SpotifyPlayer.State.EMPTY,
                json("{\"configured\":true,\"connected\":true,\"premium\":true}"));
        s = SpotifyPlayer.applyPlayer(s, json("{\"playing\":true,\"progressMs\":10000,\"shuffle\":true,"
                + "\"repeat\":\"context\",\"device\":{\"id\":\"abc\",\"name\":\"PC\",\"volume\":40,\"volumeSupported\":true},"
                + "\"track\":{\"title\":\"Song\",\"artist\":\"A, B\",\"uri\":\"spotify:track:x\",\"durationMs\":20000}}"), 1_000);
        assertEquals("Song", s.track.title);
        assertEquals(40, s.volume);
        assertTrue(s.volumeSupported);
        assertEquals("context", s.repeat);
        assertEquals(15_000, s.progressNow(6_000));
        assertEquals(20_000, s.progressNow(60_000), "never past the end");
    }

    @Test
    void pausedProgressStaysPut() {
        SpotifyPlayer.State s = SpotifyPlayer.applyPlayer(SpotifyPlayer.State.EMPTY, json(
                "{\"playing\":false,\"progressMs\":5000,\"track\":{\"title\":\"T\",\"durationMs\":9000}}"), 0);
        assertEquals(5_000, s.progressNow(50_000));
    }

    @Test
    void nothingPlayingHasNoTrack() {
        SpotifyPlayer.State s = SpotifyPlayer.applyPlayer(SpotifyPlayer.State.EMPTY,
                json("{\"playing\":false,\"track\":null,\"device\":null}"), 0);
        assertNull(s.track);
        assertEquals(-1, s.volume);
        assertFalse(s.volumeSupported);
    }

    @Test
    void theApiEnvelopeAndItsReasonsAreRead() {
        assertEquals("NO_DEVICE", SpotifyPlayer.reason("{\"success\":false,\"reason\":\"NO_DEVICE\",\"error\":\"x\"}"));
        assertEquals("x", SpotifyPlayer.message("{\"success\":false,\"reason\":\"NO_DEVICE\",\"error\":\"x\"}"));
        assertEquals("PC", SpotifyPlayer.data("{\"success\":true,\"data\":{\"name\":\"PC\"}}").get("name").getAsString());
        assertNull(SpotifyPlayer.data("not json"));
    }

    @Test
    void timesReadLikeAPlayer() {
        assertEquals("0:00", SpotifyPlayer.time(0));
        assertEquals("3:05", SpotifyPlayer.time(185_400));
        assertEquals("1:00:01", SpotifyPlayer.time(3_601_000));
    }
}
