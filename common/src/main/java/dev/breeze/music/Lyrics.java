package dev.breeze.music;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.breeze.net.BreezeApi;
import dev.breeze.net.SpotifyPlayer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Lyrics for the song that is playing, from LRCLIB (lrclib.net): a free,
 * open lyrics database that needs no account or key. Only the song's title,
 * artist and length are sent, and only while the Lyrics module is on or the
 * Music screen shows its Lyrics tab.
 *
 * The contract, read from the service's source (tranxuanthang/lrclib):
 * GET /api/get?track_name&artist_name[&duration in seconds, matched within
 * 2 s] answers one record or 404; GET /api/search?track_name&artist_name
 * (or ?q) answers up to 20 records. A record has syncedLyrics (LRC),
 * plainLyrics, instrumental and duration, any of which may be null.
 *
 * Nothing here blocks: {@link #of} answers from memory or LOADING and starts
 * at most one lookup at a time. Results are kept for the session (48 songs);
 * "not found" is asked again after half an hour, a failure after a minute.
 * Everything fetched is size-limited and cleaned ({@link Lrc}) before it is
 * kept.
 */
public final class Lyrics {

    private Lyrics() {}

    public enum Status { LOADING, SYNCED, PLAIN, INSTRUMENTAL, NOT_FOUND, FAILED }

    public static final class Result {
        public final Status status;
        /** Timed lines, when SYNCED. */
        public final List<Lrc.Line> lines;
        /** Untimed lines, when PLAIN. */
        public final List<String> plain;

        Result(Status status, List<Lrc.Line> lines, List<String> plain) {
            this.status = status;
            this.lines = lines == null ? List.of() : lines;
            this.plain = plain == null ? List.of() : plain;
        }

        static Result of(Status status) {
            return new Result(status, null, null);
        }
    }

    private static final Result LOADING = Result.of(Status.LOADING);

    private static final String DEFAULT_BASE = "https://lrclib.net";
    private static final int MAX_BODY = 1_500_000;
    private static final int KEPT = 48;
    private static final long NOT_FOUND_FOR_MS = 30 * 60_000L;
    private static final long FAILED_FOR_MS = 60_000L;
    /** A song must be showing this long before it is looked up, so skipping through songs asks for none of them. */
    private static final long SETTLE_MS = 700;
    /** Timed lyrics are only used when their song is this close in length to the one playing. */
    static final long LENGTH_TOLERANCE_MS = 3_000;
    private static final Gson GSON = new Gson();

    private static final class Kept {
        final Result result;
        final long until;

        Kept(Result result, long until) {
            this.result = result;
            this.until = until;
        }
    }

    private static final Map<String, Kept> KEPT_RESULTS = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Kept> eldest) {
            return size() > KEPT;
        }
    };
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static String waitingKey;
    private static long waitingSince;

    /**
     * The lyrics of a song, or LOADING while they are looked up (which this
     * call starts). {@code durationMs} is 0 or less when not known.
     */
    public static Result of(String title, String artist, String album, long durationMs) {
        if (title == null || title.isBlank()) return Result.of(Status.NOT_FOUND);
        String key = key(title, artist, durationMs);
        long now = System.currentTimeMillis();
        synchronized (KEPT_RESULTS) {
            Kept e = KEPT_RESULTS.get(key);
            if (e != null && now < e.until) return e.result;
            if (!key.equals(waitingKey)) {
                waitingKey = key;
                waitingSince = now;
                return LOADING;
            }
            if (now - waitingSince < SETTLE_MS) return LOADING;
        }
        if (BUSY.compareAndSet(false, true)) {
            try {
                lookUp(title.trim(), artist == null ? "" : artist.trim(), durationMs)
                        .whenComplete((result, err) -> {
                            Result r = result != null && err == null ? result : Result.of(Status.FAILED);
                            keep(key, r, System.currentTimeMillis());
                            BUSY.set(false);
                        });
            } catch (Throwable t) {
                BUSY.set(false);
                keep(key, Result.of(Status.FAILED), now);
            }
        }
        return LOADING;
    }

    /** Package-private for the tests. */
    static void keep(String key, Result r, long now) {
        long until = switch (r.status) {
            case NOT_FOUND -> now + NOT_FOUND_FOR_MS;
            case FAILED -> now + FAILED_FOR_MS;
            default -> Long.MAX_VALUE;
        };
        synchronized (KEPT_RESULTS) {
            KEPT_RESULTS.put(key, new Kept(r, until));
        }
    }

    /** Forget a song's answer so it is looked up again (the Music screen's "Look again"). */
    public static void forget(String title, String artist, long durationMs) {
        if (title == null) return;
        synchronized (KEPT_RESULTS) {
            KEPT_RESULTS.remove(key(title, artist, durationMs));
        }
    }

    static String key(String title, String artist, long durationMs) {
        long seconds = durationMs > 0 ? Math.round(durationMs / 1000.0) : 0;
        return title.trim().toLowerCase(Locale.ROOT) + "\n" + (artist == null ? "" : artist.trim().toLowerCase(Locale.ROOT)) + "\n" + seconds;
    }

    // ── what is being sung now ──────────────────────────────────────────────

    /** The lyrics of the playing song and the line being sung. */
    public static final class Now {
        public final Result result;
        /** The line being sung (into {@code result.lines}), or -1. */
        public final int index;
        /** True when the player does not tell its position and the time since the song appeared is used. */
        public final boolean estimated;

        Now(Result result, int index, boolean estimated) {
            this.result = result;
            this.index = index;
            this.estimated = estimated;
        }
    }

    private static final Now NOTHING = new Now(Result.of(Status.NOT_FOUND), -1, false);
    private static final Clock CLOCK = new Clock();

    /**
     * For the song in {@code s}: its lyrics and the current line.
     * {@code offsetMs} moves the lyrics later (positive) or sooner.
     * Null track: nothing.
     */
    public static Now now(SpotifyPlayer.State s, long nowMs, long offsetMs) {
        SpotifyPlayer.Track t = s == null ? null : s.track;
        if (t == null || t.title.isEmpty()) return NOTHING;
        Result r = of(t.title, t.artist, t.album, t.durationMs);
        boolean known = s.positionKnown();
        long position = known ? s.progressNow(nowMs) : CLOCK.estimate(t.title + "\n" + t.artist, s.playing, nowMs);
        int index = r.status == Status.SYNCED ? Lrc.indexAt(r.lines, position - offsetMs) : -1;
        return new Now(r, index, !known);
    }

    /**
     * Time into a song for a player that does not tell: counted from when
     * the song was first seen, standing still while it is paused. Right when
     * the song was seen from its start, which a skip or a new song is.
     */
    public static final class Clock {
        private String song;
        private long playedMs;
        private long lastAt;
        private boolean wasPlaying;

        public synchronized long estimate(String song, boolean playing, long now) {
            if (!song.equals(this.song)) {
                this.song = song;
                playedMs = 0;
                lastAt = now;
                wasPlaying = playing;
                return 0;
            }
            // A long gap means nobody was watching (the module was off): that time is not counted.
            if (wasPlaying && now > lastAt && now - lastAt < 15_000) playedMs += now - lastAt;
            lastAt = now;
            wasPlaying = playing;
            return playedMs;
        }
    }

    // ── looking up ──────────────────────────────────────────────────────────

    private static String base() {
        // The self-test's stand-in service, on this computer only.
        String prop = System.getProperty("breeze.lyrics.url");
        if (prop != null && (prop.startsWith("http://127.0.0.1:") || prop.startsWith("http://localhost:"))) {
            return prop.endsWith("/") ? prop.substring(0, prop.length() - 1) : prop;
        }
        return DEFAULT_BASE;
    }

    private static CompletableFuture<Result> lookUp(String title, String artist, long durationMs) {
        String cleanTitle = cleanTitle(title);
        String firstArtist = firstArtist(artist);
        CompletableFuture<Result> exact;
        if (artist.isEmpty()) {
            exact = CompletableFuture.completedFuture(Result.of(Status.NOT_FOUND));
        } else {
            String q = "/api/get?track_name=" + enc(title) + "&artist_name=" + enc(artist);
            long seconds = Math.round(durationMs / 1000.0);
            if (seconds >= 1 && seconds <= 3600) q += "&duration=" + seconds;
            exact = get(q).thenApply(res -> {
                if (res.status == 404) return Result.of(Status.NOT_FOUND);
                if (res.status != 200) return Result.of(Status.FAILED);
                JsonObject o = GSON.fromJson(res.body, JsonObject.class);
                return o == null ? Result.of(Status.FAILED) : fromRecord(o);
            });
        }
        return exact.thenCompose(first -> {
            if (first.status == Status.SYNCED || first.status == Status.INSTRUMENTAL || first.status == Status.FAILED) {
                return CompletableFuture.completedFuture(first);
            }
            // Not found under the exact names, or found without times: search.
            String q = "/api/search?track_name=" + enc(cleanTitle) + (firstArtist.isEmpty() ? "" : "&artist_name=" + enc(firstArtist));
            return search(q, durationMs).thenCompose(second -> {
                if (second.status == Status.SYNCED || second.status == Status.INSTRUMENTAL) return CompletableFuture.completedFuture(second);
                Result best = better(first, second);
                // A video's title often holds both names ("Artist - Song"): one more try by words.
                boolean byWords = cleanTitle.contains(" - ") || firstArtist.isEmpty();
                if (!byWords || second.status == Status.FAILED) return CompletableFuture.completedFuture(best);
                return search("/api/search?q=" + enc(cleanTitle), durationMs).thenApply(third -> better(best, third));
            });
        });
    }

    /** Timed beats untimed beats nothing; a failure only when there is nothing else. */
    static Result better(Result a, Result b) {
        return rank(b) > rank(a) ? b : a;
    }

    private static int rank(Result r) {
        return switch (r.status) {
            case SYNCED -> 5;
            case INSTRUMENTAL -> 4;
            case PLAIN -> 3;
            case NOT_FOUND -> 2;
            case FAILED -> 1;
            default -> 0;
        };
    }

    private static CompletableFuture<Result> search(String query, long durationMs) {
        return get(query).thenApply(res -> {
            if (res.status != 200) return Result.of(Status.FAILED);
            JsonArray a = GSON.fromJson(res.body, JsonArray.class);
            return a == null ? Result.of(Status.FAILED) : pick(a, durationMs);
        });
    }

    /** One record of the service as a result. Package-private for the tests. */
    static Result fromRecord(JsonObject o) {
        String synced = str(o, "syncedLyrics");
        if (synced != null) {
            List<Lrc.Line> lines = Lrc.parse(synced);
            if (!lines.isEmpty()) return new Result(Status.SYNCED, lines, null);
        }
        if (o.has("instrumental") && o.get("instrumental").isJsonPrimitive() && o.get("instrumental").getAsBoolean()) {
            return Result.of(Status.INSTRUMENTAL);
        }
        String plain = str(o, "plainLyrics");
        if (plain != null) {
            List<String> lines = Lrc.plain(plain);
            if (!lines.isEmpty()) return new Result(Status.PLAIN, null, lines);
        }
        return Result.of(Status.NOT_FOUND);
    }

    /**
     * The best of a search's records for a song of this length: timed lyrics
     * of a song as long as this one (the closest), else, when the length is
     * not known, the first timed ones; else untimed ones. Timed lyrics of a
     * differently long recording would run out of step, so they are left.
     * Package-private for the tests.
     */
    static Result pick(JsonArray records, long durationMs) {
        Result bestSynced = null, firstPlain = null, instrumental = null;
        long bestDiff = Long.MAX_VALUE;
        int seen = 0;
        for (JsonElement e : records) {
            if (!e.isJsonObject() || ++seen > 20) continue;
            JsonObject o = e.getAsJsonObject();
            Result r = fromRecord(o);
            long length = o.has("duration") && o.get("duration").isJsonPrimitive() ? Math.round(o.get("duration").getAsDouble() * 1000) : -1;
            long diff = durationMs > 0 && length > 0 ? Math.abs(length - durationMs) : durationMs > 0 ? Long.MAX_VALUE : 0;
            if (r.status == Status.SYNCED) {
                if (diff <= LENGTH_TOLERANCE_MS && diff < bestDiff) {
                    bestSynced = r;
                    bestDiff = diff;
                }
            } else if (r.status == Status.INSTRUMENTAL) {
                if (instrumental == null && diff <= LENGTH_TOLERANCE_MS) instrumental = r;
            } else if (r.status == Status.PLAIN && firstPlain == null) {
                firstPlain = r;
            }
        }
        if (bestSynced != null) return bestSynced;
        if (instrumental != null) return instrumental;
        if (firstPlain != null) return firstPlain;
        return Result.of(Status.NOT_FOUND);
    }

    private static final Pattern VIDEO_WORDS = Pattern.compile(
            "\\s*[(\\[][^)\\]]*\\b(official|video|audio|lyrics?|visuali[sz]er|remaster(ed)?|hd|hq|4k|mv)\\b[^)\\]]*[)\\]]",
            Pattern.CASE_INSENSITIVE);

    /** A title without what video sites add to it: "(Official Video)", "[Lyrics]", "(Remastered 2011)". */
    static String cleanTitle(String title) {
        String t = VIDEO_WORDS.matcher(title).replaceAll("").trim();
        return t.isEmpty() ? title.trim() : t;
    }

    private static final Pattern MORE_ARTISTS = Pattern.compile("\\s*(,|;|\\bfeat\\.?\\s|\\bft\\.?\\s|\\bfeaturing\\s).*$", Pattern.CASE_INSENSITIVE);

    /** The first of several artists ("A, B" or "A feat. B"), without a video channel's " - Topic". */
    static String firstArtist(String artist) {
        if (artist == null) return "";
        String a = artist.trim();
        if (a.endsWith(" - Topic")) a = a.substring(0, a.length() - " - Topic".length());
        String first = MORE_ARTISTS.matcher(a).replaceFirst("").trim();
        return first.isEmpty() ? a : first;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s.length() > 200 ? s.substring(0, 200) : s, StandardCharsets.UTF_8);
    }

    private static String str(JsonObject o, String k) {
        if (!o.has(k) || !o.get(k).isJsonPrimitive()) return null;
        String s = o.get(k).getAsString();
        return s.isBlank() ? null : s;
    }

    private static volatile String clientVersion = "";

    /** The mod's version, for the name this client gives the service (the version module sets it). */
    public static void clientVersion(String version) {
        clientVersion = version != null && version.matches("[0-9A-Za-z.+_-]{1,32}") ? "/" + version : "";
    }

    private static final class Answer {
        final int status;
        final String body;

        Answer(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private static CompletableFuture<Answer> get(String pathAndQuery) {
        String agent = "BreezeClient" + clientVersion + " (https://breezeclient.net)";
        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + pathAndQuery)).timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json").header("User-Agent", agent).header("Lrclib-Client", agent).GET().build();
        return BreezeApi.http().sendAsync(req, HttpResponse.BodyHandlers.ofInputStream()).thenApply(res -> {
            try (InputStream in = res.body()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_BODY) return new Answer(599, "");
                }
                return new Answer(res.statusCode(), out.toString(StandardCharsets.UTF_8));
            } catch (Exception e) {
                return new Answer(599, "");
            }
        });
    }
}
