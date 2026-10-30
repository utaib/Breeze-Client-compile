package dev.breeze.net;

import dev.breeze.Log;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The player's own Spotify, as the Breeze API exposes it (/spotify/*).
 *
 * Music never plays inside Minecraft: Spotify's Web API controls the
 * player's Spotify app (this computer, a phone, a speaker), which is what
 * plays it. Breeze shows what is playing (the Spotify HUD module) and sends
 * commands (the Music screen).
 *
 * Nothing here blocks a game tick. {@link #tick} starts at most one request
 * of each kind on the HTTP executor and returns; the answer lands in
 * {@link #state()}, an immutable snapshot the HUD and the screen read every
 * frame. Polling only happens while something asks for it: the HUD module
 * while it is on, the Music screen while it is open, faster for the screen.
 *
 * The API answers with machine-readable reasons, and each becomes something a
 * player can act on: NOT_REGISTERED (the Spotify app's Development Mode
 * allowlist), PREMIUM_REQUIRED (only from Spotify itself, or a known free
 * account), NO_DEVICE (Spotify is not open anywhere), RECONNECT_REQUIRED.
 */
public final class SpotifyPlayer {

    private SpotifyPlayer() {}

    public enum Phase {
        /** Not asked yet. */
        UNKNOWN,
        /** No Breeze game token: the game was not started from the Breeze launcher, or sign-in is pending. */
        NOT_SIGNED_IN,
        /** The API has no Spotify credentials: the feature is off on the server. */
        NOT_CONFIGURED,
        /** This Breeze account has no Spotify connected (or it expired). */
        NOT_CONNECTED,
        /** Connected, but Spotify refuses this account (see {@link State#blocked}). */
        BLOCKED,
        /** Connected and readable. */
        READY,
    }

    public static final class Track {
        public final String title;
        public final String artist;
        public final String album;
        public final String uri;
        /** Spotify's smallest cover picture (64 px), for the HUD. */
        public final String artworkSmall;
        public final long durationMs;

        Track(String title, String artist, String album, String uri, String artworkSmall, long durationMs) {
            this.title = title == null ? "" : title;
            this.artist = artist == null ? "" : artist;
            this.album = album;
            this.uri = uri;
            this.artworkSmall = artworkSmall;
            this.durationMs = Math.max(0, durationMs);
        }
    }

    /** A playlist, a device or a search result, for the Music screen's lists. */
    public static final class Item {
        public final String id;
        public final String uri;
        public final String name;
        public final String detail;
        public final long durationMs;
        public final boolean active;

        Item(String id, String uri, String name, String detail, long durationMs, boolean active) {
            this.id = id;
            this.uri = uri;
            this.name = name == null ? "" : name;
            this.detail = detail == null ? "" : detail;
            this.durationMs = durationMs;
            this.active = active;
        }
    }

    /** One answer for a list: the items, or why there are none. */
    public static final class Listing {
        public final List<Item> items;
        public final String problem;
        /** The machine reason, when the API gave one (RECONNECT_REQUIRED, NOT_REGISTERED). */
        public final String reason;

        public Listing(List<Item> items, String problem, String reason) {
            this.items = items == null ? List.of() : Collections.unmodifiableList(items);
            this.problem = problem;
            this.reason = reason;
        }
    }

    /** Everything the HUD and the screen show, as of one moment. Immutable. */
    public static final class State {
        public final Phase phase;
        /** Why Spotify refuses this account, when phase is BLOCKED (NOT_REGISTERED, FORBIDDEN). */
        public final String blocked;
        /** null when Spotify did not say. Never false for "unknown". */
        public final Boolean premium;
        public final String displayName;
        public final boolean playing;
        public final long progressMs;
        /** When progressMs was read (System.currentTimeMillis). */
        public final long readAt;
        public final boolean shuffle;
        public final String repeat;
        /** 0 to 100, or -1 when the device does not report one. */
        public final int volume;
        public final boolean volumeSupported;
        public final String deviceName;
        public final String deviceId;
        public final Track track;
        /** The last thing the player should be told (a refused command), or null. */
        public final String message;
        public final long messageAt;
        /** The last command came back NO_DEVICE: the screen offers the account's devices. */
        public final boolean needsDevice;

        State(Phase phase, String blocked, Boolean premium, String displayName, boolean playing, long progressMs,
              long readAt, boolean shuffle, String repeat, int volume, boolean volumeSupported, String deviceName,
              String deviceId, Track track, String message, long messageAt, boolean needsDevice) {
            this.phase = phase;
            this.blocked = blocked;
            this.premium = premium;
            this.displayName = displayName;
            this.playing = playing;
            this.progressMs = progressMs;
            this.readAt = readAt;
            this.shuffle = shuffle;
            this.repeat = repeat == null ? "off" : repeat;
            this.volume = volume;
            this.volumeSupported = volumeSupported;
            this.deviceName = deviceName;
            this.deviceId = deviceId;
            this.track = track;
            this.message = message;
            this.messageAt = messageAt;
            this.needsDevice = needsDevice;
        }

        static final State EMPTY = new State(Phase.UNKNOWN, null, null, null, false, 0, 0, false, "off", -1, false,
                null, null, null, null, 0, false);

        /** Where the track is now: the last reading plus the time since, while it plays. */
        public long progressNow(long now) {
            if (track == null) return 0;
            long p = playing ? progressMs + Math.max(0, now - readAt) : progressMs;
            return track.durationMs > 0 ? Math.min(p, track.durationMs) : p;
        }

        /** Whether commands are worth offering (Premium known or unknown, and not refused outright). */
        public boolean canControl() {
            return phase == Phase.READY && !Boolean.FALSE.equals(premium);
        }

        State withPlayback(boolean playing, long progressMs, long readAt, boolean shuffle, String repeat, int volume,
                           boolean volumeSupported, String deviceName, String deviceId, Track track) {
            return new State(Phase.READY, null, premium, displayName, playing, progressMs, readAt, shuffle, repeat,
                    volume, volumeSupported, deviceName, deviceId, track, message, messageAt,
                    needsDevice && deviceName == null);
        }

        State withStatus(Phase phase, String blocked, Boolean premium, String displayName) {
            return new State(phase, blocked, premium, displayName, playing, progressMs, readAt, shuffle, repeat,
                    volume, volumeSupported, deviceName, deviceId, phase == Phase.READY ? track : null, message,
                    messageAt, needsDevice);
        }

        State withMessage(String message, long at, boolean needsDevice) {
            return new State(phase, blocked, premium, displayName, playing, progressMs, readAt, shuffle, repeat,
                    volume, volumeSupported, deviceName, deviceId, track, message, at, needsDevice);
        }

        State withPremium(Boolean premium) {
            return new State(phase, blocked, premium, displayName, playing, progressMs, readAt, shuffle, repeat,
                    volume, volumeSupported, deviceName, deviceId, track, message, messageAt, needsDevice);
        }
    }

    private static final Gson GSON = new Gson();
    /** How often the connection itself is re-read while something wants Spotify. */
    private static final long STATUS_EVERY_MS = 60_000;
    /** After "busy" (429) or "not responding" (503), wait this long before polling again. */
    private static final long BACKOFF_MS = 15_000;
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private static volatile State state = State.EMPTY;
    private static final AtomicBoolean statusInFlight = new AtomicBoolean();
    private static final AtomicBoolean playerInFlight = new AtomicBoolean();
    private static volatile long lastStatusAt;
    private static volatile long lastPlayerAt;
    private static volatile long quietUntil;
    /** Set by a command so the player is read again shortly (at readPlayerAfter), not at the next poll. */
    private static volatile boolean refreshSoon;
    private static volatile long readPlayerAfter;

    public static State state() {
        return state;
    }

    /**
     * Called every client tick. {@code everyMs} is how often the player
     * should be read right now (0: nothing on screen wants it, so nothing is
     * asked).
     */
    public static void tick(long now, long everyMs) {
        if (everyMs <= 0) return;
        if (now < quietUntil) return;
        State s = state;
        long since = now - lastStatusAt;
        boolean statusDue = switch (s.phase) {
            case UNKNOWN -> since >= 3_000;
            // Waiting for the game token: cheap to ask, nothing goes out.
            case NOT_SIGNED_IN -> since >= 5_000;
            // Connecting happens in the browser and nothing here learns that it
            // finished, so these are re-read every 20 seconds.
            case NOT_CONNECTED, BLOCKED -> since >= 20_000;
            default -> since >= STATUS_EVERY_MS;
        };
        if (statusDue) readStatus(now);
        boolean soon = refreshSoon && now >= readPlayerAfter;
        if (s.phase == Phase.READY && (soon || now - lastPlayerAt >= everyMs)) readPlayer(now);
    }

    /** Forget everything (the screen's "Check again", or after connecting). */
    public static void recheck() {
        lastStatusAt = 0;
        quietUntil = 0;
        state = state.withStatus(Phase.UNKNOWN, null, null, null);
    }

    // ── reading ─────────────────────────────────────────────────────────────

    private static void readStatus(long now) {
        if (!statusInFlight.compareAndSet(false, true)) return;
        lastStatusAt = now;
        HttpRequest.Builder b = request("/spotify/status").GET();
        if (!BreezeApi.authed(b)) {
            statusInFlight.set(false);
            state = state.withStatus(Phase.NOT_SIGNED_IN, null, null, null);
            return;
        }
        BreezeApi.http().sendAsync(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .whenComplete((res, err) -> {
                    try {
                        if (err != null) {
                            backOff();
                            return;
                        }
                        if (breezeRefused(res)) {
                            state = state.withStatus(Phase.NOT_SIGNED_IN, null, null, null);
                            return;
                        }
                        if (res.statusCode() == 429 || res.statusCode() >= 500) {
                            backOff();
                            return;
                        }
                        JsonObject data = data(res.body());
                        if (data == null) return;
                        state = applyStatus(state, data);
                        if (state.phase == Phase.READY) {
                            readPlayerAfter = 0;
                            refreshSoon = true;
                        }
                    } catch (Throwable t) {
                        Log.warn("[Breeze] Spotify status not read: {}", t.toString());
                    } finally {
                        statusInFlight.set(false);
                    }
                });
    }

    private static void readPlayer(long now) {
        if (!playerInFlight.compareAndSet(false, true)) return;
        lastPlayerAt = now;
        refreshSoon = false;
        HttpRequest.Builder b = request("/spotify/now-playing").GET();
        if (!BreezeApi.authed(b)) {
            playerInFlight.set(false);
            return;
        }
        BreezeApi.http().sendAsync(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .whenComplete((res, err) -> {
                    try {
                        if (err != null) {
                            backOff();
                            return;
                        }
                        if (breezeRefused(res)) return;
                        int code = res.statusCode();
                        if (code == 200) {
                            JsonObject data = data(res.body());
                            if (data != null) state = applyPlayer(state, data, System.currentTimeMillis());
                            return;
                        }
                        if (code == 401) {
                            // Spotify's own authorization is gone.
                            state = state.withStatus(Phase.NOT_CONNECTED, null, null, null);
                            return;
                        }
                        if (code == 403) {
                            String reason = reason(res.body());
                            if ("NOT_REGISTERED".equals(reason) || "FORBIDDEN".equals(reason)) {
                                state = state.withStatus(Phase.BLOCKED, reason, state.premium, state.displayName);
                            }
                            return;
                        }
                        if (code == 429 || code >= 500) backOff();
                    } catch (Throwable t) {
                        Log.warn("[Breeze] Spotify player not read: {}", t.toString());
                    } finally {
                        playerInFlight.set(false);
                    }
                });
    }

    private static void backOff() {
        quietUntil = System.currentTimeMillis() + BACKOFF_MS;
    }

    /** The API's /spotify/status answer, applied. Package-private for the tests. */
    static State applyStatus(State s, JsonObject d) {
        if (!bool(d, "configured", true)) return s.withStatus(Phase.NOT_CONFIGURED, null, null, null);
        if (!bool(d, "connected", false)) return s.withStatus(Phase.NOT_CONNECTED, null, null, null);
        Boolean premium = d.has("premium") && !d.get("premium").isJsonNull() ? d.get("premium").getAsBoolean() : null;
        String name = str(d, "displayName");
        String blocked = str(d, "blocked");
        if (blocked != null && !"PREMIUM_REQUIRED".equals(blocked)) return s.withStatus(Phase.BLOCKED, blocked, premium, name);
        return s.withStatus(Phase.READY, null, premium, name);
    }

    /** The API's /spotify/now-playing answer, applied. Package-private for the tests. */
    static State applyPlayer(State s, JsonObject d, long now) {
        JsonObject device = obj(d, "device");
        JsonObject t = obj(d, "track");
        Track track = t == null ? null : new Track(str(t, "title"), str(t, "artist"), str(t, "album"), str(t, "uri"),
                str(t, "artworkSmall"), num(t, "durationMs", 0));
        int volume = -1;
        boolean volumeSupported = false;
        String deviceName = null, deviceId = null;
        if (device != null) {
            deviceName = str(device, "name");
            deviceId = str(device, "id");
            if (device.has("volume") && !device.get("volume").isJsonNull()) volume = (int) num(device, "volume", -1);
            volumeSupported = bool(device, "volumeSupported", true) && volume >= 0;
        }
        return s.withPlayback(bool(d, "playing", false), num(d, "progressMs", 0), now, bool(d, "shuffle", false),
                str(d, "repeat"), volume, volumeSupported, deviceName, deviceId, track);
    }

    // ── commands ────────────────────────────────────────────────────────────

    public static void togglePlay() {
        State s = state;
        if (s.playing) command("/spotify/pause", null, () -> state = flipPlaying(false));
        else command("/spotify/play", null, () -> state = flipPlaying(true));
    }

    public static void next() {
        command("/spotify/next", null, null);
    }

    public static void previous() {
        command("/spotify/previous", null, null);
    }

    public static void volume(int percent) {
        int p = Math.max(0, Math.min(100, percent));
        JsonObject body = new JsonObject();
        body.addProperty("percent", p);
        command("/spotify/volume", body, null);
    }

    public static void seek(long positionMs) {
        JsonObject body = new JsonObject();
        body.addProperty("positionMs", Math.max(0, positionMs));
        command("/spotify/seek", body, null);
    }

    public static void shuffle(boolean on) {
        JsonObject body = new JsonObject();
        body.addProperty("state", on);
        command("/spotify/shuffle", body, null);
    }

    /** off, then context (the playlist or album), then track, then off. */
    public static void cycleRepeat() {
        String next = switch (state.repeat) {
            case "off" -> "context";
            case "context" -> "track";
            default -> "off";
        };
        JsonObject body = new JsonObject();
        body.addProperty("state", next);
        command("/spotify/repeat", body, null);
    }

    /** Play a track, or a playlist (optionally from one of its tracks). */
    public static void play(String uri, String contextUri) {
        JsonObject body = new JsonObject();
        if (uri != null) body.addProperty("uri", uri);
        if (contextUri != null) body.addProperty("contextUri", contextUri);
        command("/spotify/play-item", body, null);
    }

    public static void transfer(String deviceId) {
        JsonObject body = new JsonObject();
        body.addProperty("deviceId", deviceId);
        command("/spotify/transfer", body, null);
    }

    private static State flipPlaying(boolean playing) {
        State s = state;
        long now = System.currentTimeMillis();
        return s.withPlayback(playing, s.progressNow(now), now, s.shuffle, s.repeat, s.volume, s.volumeSupported,
                s.deviceName, s.deviceId, s.track);
    }

    private static void command(String path, JsonObject body, Runnable optimistic) {
        HttpRequest.Builder b = request(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body.toString(), StandardCharsets.UTF_8));
        if (!BreezeApi.authed(b)) {
            say("Breeze is still signing in. Try again in a moment.", false);
            return;
        }
        BreezeApi.http().sendAsync(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .whenComplete((res, err) -> {
                    try {
                        if (err != null) {
                            say("Could not reach Breeze. Check your connection.", false);
                            return;
                        }
                        if (breezeRefused(res)) {
                            say("Breeze is signing in again. Try again in a moment.", false);
                            return;
                        }
                        int code = res.statusCode();
                        if (code >= 200 && code < 300) {
                            if (optimistic != null) optimistic.run();
                            state = state.withMessage(null, 0, false);
                            // Spotify applies a command a moment after accepting
                            // it; reading at once would often show the old track.
                            readPlayerAfter = System.currentTimeMillis() + 400;
                            refreshSoon = true;
                            return;
                        }
                        String reason = reason(res.body());
                        String message = message(res.body());
                        if ("PREMIUM_REQUIRED".equals(reason)) {
                            state = state.withPremium(Boolean.FALSE);
                            say("Spotify Premium is needed to control music. You can still see what is playing.", false);
                        } else if ("NOT_REGISTERED".equals(reason)) {
                            state = state.withStatus(Phase.BLOCKED, reason, state.premium, state.displayName);
                        } else if ("NO_DEVICE".equals(reason)) {
                            say("Spotify is not open anywhere. Open Spotify on this computer or your phone, or pick a device.", true);
                        } else if (code == 401) {
                            state = state.withStatus(Phase.NOT_CONNECTED, null, null, null);
                        } else {
                            say(message != null ? message : "Spotify refused that.", false);
                        }
                    } catch (Throwable t) {
                        Log.warn("[Breeze] Spotify command failed: {}", t.toString());
                    }
                });
    }

    private static void say(String message, boolean needsDevice) {
        state = state.withMessage(message, System.currentTimeMillis(), needsDevice);
    }

    // ── lists ───────────────────────────────────────────────────────────────

    public static CompletableFuture<Listing> search(String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) return CompletableFuture.completedFuture(new Listing(List.of(), null, null));
        return list("/spotify/search?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8), "tracks", o -> new Item(
                str(o, "id"), str(o, "uri"), str(o, "title"), str(o, "artist"), num(o, "durationMs", 0), false));
    }

    public static CompletableFuture<Listing> playlists() {
        return list("/spotify/playlists", "playlists", o -> {
            long tracks = num(o, "tracks", -1);
            String owner = str(o, "owner");
            String detail = (tracks >= 0 ? tracks + (tracks == 1 ? " song" : " songs") : "") + (owner != null ? (tracks >= 0 ? ", " : "") + owner : "");
            return new Item(str(o, "id"), str(o, "uri"), str(o, "name"), detail, 0, false);
        });
    }

    public static CompletableFuture<Listing> devices() {
        return list("/spotify/devices", "devices", o -> new Item(str(o, "id"), null, str(o, "name"),
                str(o, "type"), 0, bool(o, "active", false)));
    }

    /** The Spotify sign-in page for this account, to open in the player's browser. */
    public static CompletableFuture<String> loginUrl() {
        HttpRequest.Builder b = request("/spotify/login").GET();
        if (!BreezeApi.authed(b)) return CompletableFuture.completedFuture(null);
        return BreezeApi.http().sendAsync(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle((res, err) -> {
                    if (err != null || res.statusCode() != 200) return null;
                    JsonObject d = data(res.body());
                    String url = d == null ? null : str(d, "url");
                    return url != null && url.startsWith("https://accounts.spotify.com/") ? url : null;
                });
    }

    private interface Reader {
        Item read(JsonObject o);
    }

    private static CompletableFuture<Listing> list(String path, String key, Reader reader) {
        HttpRequest.Builder b = request(path).GET();
        if (!BreezeApi.authed(b)) {
            return CompletableFuture.completedFuture(new Listing(List.of(), "Breeze is still signing in.", null));
        }
        return BreezeApi.http().sendAsync(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle((res, err) -> {
                    if (err != null) return new Listing(List.of(), "Could not reach Breeze.", null);
                    if (breezeRefused(res)) return new Listing(List.of(), "Breeze is signing in again.", null);
                    if (res.statusCode() != 200) {
                        String reason = reason(res.body());
                        String message = message(res.body());
                        return new Listing(List.of(), message != null ? message : "Spotify did not answer.", reason);
                    }
                    JsonObject d = data(res.body());
                    List<Item> out = new ArrayList<>();
                    if (d != null && d.has(key) && d.get(key).isJsonArray()) {
                        for (JsonElement e : d.getAsJsonArray(key)) {
                            if (e.isJsonObject()) out.add(reader.read(e.getAsJsonObject()));
                        }
                    }
                    return new Listing(out, null, null);
                });
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(BreezeApi.uri(path)).timeout(TIMEOUT).header("Accept", "application/json");
    }

    /**
     * Breeze itself refused the game token (not Spotify): the API's own
     * "Authentication required" or "Invalid or expired token". Reported to
     * BreezeApi so it signs in again.
     */
    private static boolean breezeRefused(HttpResponse<String> res) {
        int code = res.statusCode();
        if (code != 401 && code != 403) return false;
        String body = res.body() == null ? "" : res.body();
        if (body.contains("Authentication required") || body.contains("Invalid or expired token")
                || body.contains("cannot be used for account operations")) {
            BreezeApi.onUnauthorized(code);
            return true;
        }
        return false;
    }

    /** The "data" object of the API's {success, data} envelope (or the body itself). */
    static JsonObject data(String body) {
        try {
            JsonObject root = GSON.fromJson(body, JsonObject.class);
            if (root == null) return null;
            if (root.has("data") && root.get("data").isJsonObject()) return root.getAsJsonObject("data");
            return root;
        } catch (Throwable t) {
            return null;
        }
    }

    static String reason(String body) {
        JsonObject root = data(body);
        return root == null ? null : str(root, "reason");
    }

    static String message(String body) {
        try {
            JsonObject root = GSON.fromJson(body, JsonObject.class);
            if (root == null) return null;
            String e = str(root, "error");
            return e != null ? e : str(root, "message");
        } catch (Throwable t) {
            return null;
        }
    }

    private static JsonObject obj(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : null;
    }

    private static String str(JsonObject o, String k) {
        if (!o.has(k) || o.get(k).isJsonNull()) return null;
        try {
            String s = o.get(k).getAsString();
            return s.isEmpty() ? null : s;
        } catch (Throwable t) {
            return null;
        }
    }

    private static long num(JsonObject o, String k, long otherwise) {
        if (!o.has(k) || o.get(k).isJsonNull()) return otherwise;
        try {
            return o.get(k).getAsLong();
        } catch (Throwable t) {
            return otherwise;
        }
    }

    private static boolean bool(JsonObject o, String k, boolean otherwise) {
        if (!o.has(k) || o.get(k).isJsonNull()) return otherwise;
        try {
            return o.get(k).getAsBoolean();
        } catch (Throwable t) {
            return otherwise;
        }
    }

    /** m:ss, or h:mm:ss past an hour. */
    public static String time(long ms) {
        long s = Math.max(0, ms) / 1000;
        long h = s / 3600, m = (s / 60) % 60, sec = s % 60;
        return h > 0 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, sec)
                : String.format(java.util.Locale.ROOT, "%d:%02d", m, sec);
    }

    /** What to tell the player about a state that cannot play (null when it can). */
    public static String explain(State s) {
        switch (s.phase) {
            case UNKNOWN:
                return "Checking Spotify.";
            case NOT_SIGNED_IN:
                return "Start Minecraft from the Breeze launcher, signed in, to use Spotify here.";
            case NOT_CONFIGURED:
                return "Spotify is not available right now.";
            case NOT_CONNECTED:
                return "Connect Spotify to see and control your music in game.";
            case BLOCKED:
                return "NOT_REGISTERED".equals(s.blocked)
                        ? "Spotify has not allowed this account in Breeze's Spotify app yet. While the app is in Spotify's test mode, only accounts the Breeze team adds can use it."
                        : "Spotify refused this account.";
            default:
                return null;
        }
    }

}
