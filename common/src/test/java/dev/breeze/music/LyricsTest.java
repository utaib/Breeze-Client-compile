package dev.breeze.music;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LyricsTest {

    private static final Gson GSON = new Gson();

    private static JsonObject record(String json) {
        return GSON.fromJson(json, JsonObject.class);
    }

    private static JsonArray records(String json) {
        return GSON.fromJson(json, JsonArray.class);
    }

    @Test
    void aRecordWithTimesIsSynced() {
        Lyrics.Result r = Lyrics.fromRecord(record("{\"id\":1,\"trackName\":\"Song\",\"duration\":200.0,\"instrumental\":false,"
                + "\"plainLyrics\":\"one\\ntwo\",\"syncedLyrics\":\"[00:10.00] one\\n[00:20.00] two\"}"));
        assertEquals(Lyrics.Status.SYNCED, r.status);
        assertEquals(2, r.lines.size());
        assertEquals("two", r.lines.get(1).text);
    }

    @Test
    void untimedInstrumentalAndEmptyRecords() {
        assertEquals(Lyrics.Status.PLAIN, Lyrics.fromRecord(record("{\"plainLyrics\":\"one\\ntwo\",\"syncedLyrics\":null}")).status);
        assertEquals(Lyrics.Status.INSTRUMENTAL, Lyrics.fromRecord(record("{\"instrumental\":true,\"plainLyrics\":null,\"syncedLyrics\":null}")).status);
        assertEquals(Lyrics.Status.NOT_FOUND, Lyrics.fromRecord(record("{\"instrumental\":false,\"plainLyrics\":null,\"syncedLyrics\":null}")).status);
        // "Timed" text without a single time is not timed.
        assertEquals(Lyrics.Status.PLAIN, Lyrics.fromRecord(record("{\"plainLyrics\":\"words\",\"syncedLyrics\":\"words\"}")).status);
        assertEquals(Lyrics.Status.NOT_FOUND, Lyrics.fromRecord(record("{\"syncedLyrics\":42}")).status);
    }

    private static final String FOUND = "["
            + "{\"duration\":250.0,\"syncedLyrics\":\"[00:01.00] live version\"},"
            + "{\"duration\":203.0,\"syncedLyrics\":\"[00:01.00] three seconds off\"},"
            + "{\"duration\":200.4,\"syncedLyrics\":\"[00:01.00] the right one\"},"
            + "{\"duration\":200.0,\"plainLyrics\":\"plain words\",\"syncedLyrics\":null}"
            + "]";

    @Test
    void theSearchResultOfTheSameLengthWins() {
        Lyrics.Result r = Lyrics.pick(records(FOUND), 200_000);
        assertEquals(Lyrics.Status.SYNCED, r.status);
        assertEquals("the right one", r.lines.get(0).text);
        // Length not known: the first timed one, as ranked.
        assertEquals("live version", Lyrics.pick(records(FOUND), 0).lines.get(0).text);
    }

    @Test
    void timedLyricsOfADifferentRecordingAreLeft() {
        // Only the long live version and plain words: timed ones would run out of step.
        Lyrics.Result r = Lyrics.pick(records("[{\"duration\":250.0,\"syncedLyrics\":\"[00:01.00] live\"},"
                + "{\"duration\":250.0,\"plainLyrics\":\"plain words\"}]"), 200_000);
        assertEquals(Lyrics.Status.PLAIN, r.status);
        assertEquals(Lyrics.Status.NOT_FOUND, Lyrics.pick(records("[]"), 200_000).status);
        assertEquals(Lyrics.Status.NOT_FOUND, Lyrics.pick(records("[42, \"x\"]"), 200_000).status);
        assertEquals(Lyrics.Status.INSTRUMENTAL, Lyrics.pick(records("[{\"duration\":201.0,\"instrumental\":true}]"), 200_000).status);
    }

    @Test
    void theBetterOfTwoAnswers() {
        Lyrics.Result synced = Lyrics.fromRecord(record("{\"syncedLyrics\":\"[00:01.00] x\"}"));
        Lyrics.Result plain = Lyrics.fromRecord(record("{\"plainLyrics\":\"x\"}"));
        Lyrics.Result none = Lyrics.Result.of(Lyrics.Status.NOT_FOUND);
        Lyrics.Result failed = Lyrics.Result.of(Lyrics.Status.FAILED);
        assertSame(synced, Lyrics.better(plain, synced));
        assertSame(plain, Lyrics.better(plain, none));
        assertSame(plain, Lyrics.better(plain, failed));
        assertSame(none, Lyrics.better(none, failed), "not found is an answer; a failure is not");
    }

    @Test
    void titlesAndArtistsAsVideoSitesWriteThem() {
        assertEquals("Blinding Lights", Lyrics.cleanTitle("Blinding Lights (Official Video)"));
        assertEquals("Artist - Song", Lyrics.cleanTitle("Artist - Song [Official Music Video] (HD)"));
        assertEquals("Song (feat. Someone)", Lyrics.cleanTitle("Song (feat. Someone) (Lyrics)"));
        assertEquals("Song (Live)", Lyrics.cleanTitle("Song (Live)"));
        assertEquals("(Official Audio)", Lyrics.cleanTitle("(Official Audio)"), "never empty");
        assertEquals("The Weeknd", Lyrics.firstArtist("The Weeknd, Daft Punk"));
        assertEquals("The Weeknd", Lyrics.firstArtist("The Weeknd feat. Daft Punk"));
        assertEquals("Simon & Garfunkel", Lyrics.firstArtist("Simon & Garfunkel"));
        assertEquals("Queen", Lyrics.firstArtist("Queen - Topic"));
        assertEquals("", Lyrics.firstArtist(null));
    }

    @Test
    void oneKeyPerSongWhateverTheCase() {
        assertEquals(Lyrics.key("Song", "Artist", 200_400), Lyrics.key(" song ", "ARTIST", 199_600));
        assertFalse(Lyrics.key("Song", "Artist", 200_000).equals(Lyrics.key("Song", "Artist", 250_000)));
        assertEquals(Lyrics.key("Song", null, 0), Lyrics.key("Song", "", -1));
    }

    @Test
    void aKeptAnswerComesBackWithoutAsking() {
        Lyrics.Result synced = Lyrics.fromRecord(record("{\"syncedLyrics\":\"[00:10.00] kept line\"}"));
        Lyrics.keep(Lyrics.key("Kept Song", "Kept Artist", 180_000), synced, System.currentTimeMillis());
        assertSame(synced, Lyrics.of("Kept Song", "Kept Artist", null, 180_000));
        assertEquals(Lyrics.Status.NOT_FOUND, Lyrics.of("", "x", null, 0).status);
    }

    @Test
    void aClockForPlayersThatDoNotTellTheirPosition() {
        Lyrics.Clock c = new Lyrics.Clock();
        assertEquals(0, c.estimate("a", true, 1_000));
        assertEquals(2_000, c.estimate("a", true, 3_000));
        // Paused at 3 s: the pause is noticed at this reading, and the time after it is not counted.
        assertEquals(4_000, c.estimate("a", false, 5_000));
        assertEquals(4_000, c.estimate("a", false, 9_000));
        assertEquals(4_000, c.estimate("a", true, 10_000));
        assertEquals(5_500, c.estimate("a", true, 11_500));
        // Nobody asked for a minute (the module was off): that minute is not added.
        assertEquals(5_500, c.estimate("a", true, 71_500));
        // A new song starts from nothing.
        assertEquals(0, c.estimate("b", true, 72_000));
        assertTrue(c.estimate("b", true, 72_500) == 500);
    }
}
