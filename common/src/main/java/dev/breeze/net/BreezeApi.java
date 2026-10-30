package dev.breeze.net;

import dev.breeze.Log;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The one place the mod learns where the Breeze API is and how to prove who it is.
 *
 * Before this, every networked class built its own HttpClient and read the base
 * URL out of BreezePresence, which in turn came from a config file seeded with a
 * plain-HTTP raw IP. That address stopped answering, so every social feature was
 * dead in the field, and none of those requests carried a credential: the API
 * trusted whatever uuid was in the path, which let anyone read anyone's DMs.
 *
 * The API now requires a Breeze token whose uuid matches the path. The launcher
 * hands the game its account token; this class trades it once for a short-lived
 * game token (audience "breeze-game", refused by every account route) and
 * attaches that to each request. The account token itself is never sent
 * anywhere except the exchange.
 *
 * Nothing here may block a game tick. The exchange runs on the HTTP executor,
 * and a caller that asks for a token before one exists gets null and skips that
 * poll; the next poll a few seconds later finds it ready.
 */
public final class BreezeApi {

    /** Production API. Overridable with -Dbreeze.api.url for development. */
    private static final String DEFAULT_BASE = "https://api.breezeclient.net";

    /** The launcher's placeholder when it has no Breeze account token to give. */
    private static final String OFFLINE_PLACEHOLDER = "bz-offline";

    /** Refresh this long before expiry, so a poll never goes out with a token about to lapse. */
    private static final long REFRESH_MARGIN_MS = 5 * 60_000L;
    /** Used when the exchange response has no parseable expiry. */
    private static final long ASSUMED_LIFETIME_MS = 30 * 60_000L;
    /** Network failure or a server error: worth retrying, but not every tick. */
    private static final long FAILURE_RETRY_MS = 60_000L;
    /** No launcher token found; re-reading costs nothing on the network. */
    private static final long NO_CREDENTIAL_RETRY_MS = 60_000L;
    /**
     * The launcher token was refused, or the API rate limited the exchange.
     *
     * /auth/game-session sits behind the API's auth limiter, which is shared
     * with the launcher's own sign-in and allows only a handful of calls per
     * quarter hour per address. Retrying a refused credential quickly would
     * lock the player out of signing in to the launcher as well.
     */
    private static final long REJECTED_RETRY_MS = 15 * 60_000L;
    /** Repeated 401/403 on a fresh token back off from here up to REJECTED_RETRY_MS. */
    private static final long UNAUTHORIZED_BACKOFF_MS = 60_000L;
    /**
     * A 401/403 this soon after a new token was obtained came from a request
     * built with the previous token, so it says nothing about the current one.
     * Request timeouts are all shorter than this.
     */
    private static final long STALE_REJECTION_WINDOW_MS = 12_000L;
    /** A token that survived this long was good; its eventual rejection is expiry, not a loop. */
    private static final long LONG_LIVED_TOKEN_MS = 10 * 60_000L;
    /** Minimum spacing between one successful exchange and the next attempt. */
    private static final long MIN_EXCHANGE_GAP_MS = 60_000L;

    private static final Gson GSON = new Gson();
    private static final AtomicInteger THREADS = new AtomicInteger();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "Breeze-Http-" + THREADS.incrementAndGet());
        // Daemon, or a pending request would keep the game process alive after
        // the window closes.
        t.setDaemon(true);
        return t;
    });
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(6))
            .executor(EXECUTOR)
            .build();

    /** Where the last exchange attempt left things, for status text. */
    public enum AuthState {
        /** A game token is held and current. */
        READY,
        /** An exchange is in flight or about to start. */
        SIGNING_IN,
        /** No launcher credential, or the API refused it. Retrying will not help. */
        NOT_SIGNED_IN,
        /** The API could not be reached, or answered with an error. */
        UNREACHABLE
    }

    private static volatile String base;
    private static volatile URI baseUri;
    private static volatile String launcherToken;

    private static final Object LOCK = new Object();
    private static volatile String token;
    private static volatile long tokenObtainedAt;
    private static volatile long refreshAt;
    private static volatile long expiresAt;
    private static volatile long nextAttemptAt;
    private static volatile long unauthorizedBackoff;
    private static volatile AuthState state = AuthState.SIGNING_IN;
    private static final AtomicBoolean EXCHANGING = new AtomicBoolean();

    private BreezeApi() {}

    /** The shared client. One connection pool and one small thread pool for the whole mod. */
    public static HttpClient http() {
        return HTTP;
    }

    /** API base URL with no trailing slash. */
    public static String baseUrl() {
        String b = base;
        if (b == null) {
            String prop = System.getProperty("breeze.api.url");
            b = prop != null && prop.trim().startsWith("http") ? prop.trim() : DEFAULT_BASE;
            while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
            base = b;
        }
        return b;
    }

    /** The base URL parsed once, for callers that open raw sockets rather than HTTP requests. */
    public static URI baseUri() {
        URI u = baseUri;
        if (u == null) {
            u = URI.create(baseUrl());
            baseUri = u;
        }
        return u;
    }

    /** An absolute API URI for a path that starts with a slash. */
    public static URI uri(String path) {
        return URI.create(baseUrl() + path);
    }

    /**
     * The token the launcher handed the game, or null when there is none.
     *
     * The session file is preferred because a -D property is visible to any
     * process that can list command lines. The file is read once and the value
     * held in memory, since the launcher is free to delete it once the game has
     * started.
     */
    static String launcherToken() {
        String cached = launcherToken;
        if (cached != null) return cached;

        String fromFile = readSessionFile();
        if (usable(fromFile)) {
            launcherToken = fromFile;
            return fromFile;
        }
        String prop = System.getProperty("breeze.session.token");
        if (usable(prop)) {
            launcherToken = prop.trim();
            return launcherToken;
        }
        return null;
    }

    private static String readSessionFile() {
        String file = System.getProperty("breeze.session.file");
        if (file == null || file.isBlank()) return null;
        try {
            Path p = Paths.get(file.trim());
            if (!Files.isRegularFile(p)) return null;
            String text = Files.readString(p, StandardCharsets.UTF_8);
            // A BOM is legal in a UTF-8 file and Gson rejects it.
            if (!text.isEmpty() && text.charAt(0) == 0xFEFF) text = text.substring(1);
            JsonObject root = GSON.fromJson(text, JsonObject.class);
            if (root == null) return null;
            JsonElement t = root.get("token");
            return t == null || t.isJsonNull() ? null : t.getAsString().trim();
        } catch (Throwable t) {
            // The message may quote file contents, so only the type is logged.
            Log.warn("[Breeze] session file could not be read ({})", t.getClass().getSimpleName());
            return null;
        }
    }

    private static boolean usable(String value) {
        if (value == null) return false;
        String v = value.trim();
        // A game token is not a launcher token: it cannot be exchanged, only used.
        return !v.isEmpty() && !v.startsWith(OFFLINE_PLACEHOLDER) && !GAME_AUDIENCE.equals(jwtClaim(v, "aud"));
    }

    private static final String GAME_AUDIENCE = "breeze-game";
    private static volatile boolean issuedTokenChecked;

    /**
     * Take a game token the launcher already issued, if the session file holds one.
     *
     * Runs once. The file is deleted after reading so the token does not sit on
     * disk for the rest of the session, where any other mod or process could
     * pick it up. Returns true when a usable game token was adopted.
     */
    private static boolean adoptIssuedGameToken(long now) {
        if (issuedTokenChecked) return false;
        issuedTokenChecked = true;

        String file = System.getProperty("breeze.session.file");
        String candidate = readSessionFile();
        if (candidate == null || !GAME_AUDIENCE.equals(jwtClaim(candidate, "aud"))) return false;

        deleteSessionFile(file);
        String exp = jwtClaim(candidate, "exp");
        long expiresMillis;
        try {
            expiresMillis = Long.parseLong(exp) * 1000L;
        } catch (NumberFormatException e) {
            return false;
        }
        if (expiresMillis <= now) {
            Log.info("[Breeze] the launcher's game session had already expired");
            return false;
        }
        synchronized (LOCK) {
            token = candidate;
            tokenObtainedAt = now;
            expiresAt = expiresMillis;
            // It cannot be refreshed from inside the game, so it is used until it
            // expires; the next launch issues a new one.
            refreshAt = expiresMillis;
            state = AuthState.READY;
        }
        Log.info("[Breeze] signed in with the launcher's game session");
        return true;
    }

    private static void deleteSessionFile(String file) {
        if (file == null || file.isBlank()) return;
        try {
            Files.deleteIfExists(Paths.get(file.trim()));
        } catch (Throwable t) {
            Log.warn("[Breeze] could not remove the session file ({})", t.getClass().getSimpleName());
        }
    }

    /** One claim from a JWT payload, without verifying it. The server verifies. */
    static String jwtClaim(String jwt, String claim) {
        if (jwt == null) return null;
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) return null;
        try {
            byte[] decoded = java.util.Base64.getUrlDecoder().decode(parts[1]);
            JsonObject payload = GSON.fromJson(new String(decoded, StandardCharsets.UTF_8), JsonObject.class);
            JsonElement value = payload == null ? null : payload.get(claim);
            return value == null || value.isJsonNull() ? null : value.getAsString();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The current game token, or null when one is not available yet.
     *
     * Never blocks. When there is no token, or it is due for refresh, this
     * starts an exchange on the HTTP executor and returns immediately. A token
     * inside its refresh window but not yet expired is still returned, so polls
     * keep working while the replacement is fetched.
     */
    public static String gameToken() {
        long now = System.currentTimeMillis();
        String t = token;
        if (t != null && now < refreshAt) return t;
        requestExchange(now);
        return t != null && now < expiresAt ? t : null;
    }

    /** Start the exchange early so the first social poll already has a token. */
    public static void warmUp() {
        gameToken();
    }

    public static AuthState authState() {
        if (token != null && System.currentTimeMillis() < expiresAt) return AuthState.READY;
        // The last exchange succeeded but that token has since lapsed, so the
        // replacement is what is pending.
        AuthState s = state;
        return s == AuthState.READY ? AuthState.SIGNING_IN : s;
    }

    /**
     * Report a 401 or 403 from an authenticated call.
     *
     * Drops the cached token so the next poll exchanges again, which is what
     * recovers from a token that expired or was revoked server-side. Repeated
     * rejections of fresh tokens back off, because re-exchanging cannot fix a
     * mismatch and each exchange spends the shared auth rate limit.
     */
    public static void onUnauthorized(int status) {
        if (status != 401 && status != 403) return;
        synchronized (LOCK) {
            if (token == null) return;
            long now = System.currentTimeMillis();
            long age = now - tokenObtainedAt;
            if (age < STALE_REJECTION_WINDOW_MS) return;
            if (age > LONG_LIVED_TOKEN_MS) unauthorizedBackoff = 0;
            token = null;
            refreshAt = 0;
            expiresAt = 0;
            state = AuthState.SIGNING_IN;
            nextAttemptAt = Math.max(nextAttemptAt, now + unauthorizedBackoff);
            unauthorizedBackoff = unauthorizedBackoff == 0
                    ? UNAUTHORIZED_BACKOFF_MS
                    : Math.min(unauthorizedBackoff * 2, REJECTED_RETRY_MS);
            Log.info("[Breeze] game token rejected (HTTP {}), will sign in again", status);
        }
    }

    /**
     * Attach the game token to a request.
     *
     * Returns false when no token is available yet, so a caller hitting a route
     * that requires one can skip this poll instead of collecting a 401. Callers
     * of public routes may ignore the result and send the request anyway.
     */
    public static boolean authed(HttpRequest.Builder builder) {
        String t = gameToken();
        if (t == null) return false;
        builder.header("Authorization", "Bearer " + t);
        return true;
    }

    private static void requestExchange(long now) {
        if (now < nextAttemptAt) return;
        // One exchange at a time. Every tick of every poller lands here while
        // the first is in flight.
        if (!EXCHANGING.compareAndSet(false, true)) return;
        try {
            // Reading the session file is disk I/O, so even that happens off
            // the calling thread, which is usually the client tick.
            EXECUTOR.execute(BreezeApi::exchange);
        } catch (Throwable t) {
            EXCHANGING.set(false);
        }
    }

    private static void exchange() {
        long started = System.currentTimeMillis();
        // The launcher mints a short-lived game token itself and writes it to the
        // session file, so the long-lived account token never enters the game
        // process. Such a token is adopted as-is: presenting it to
        // /auth/game-session would be refused, because a game token deliberately
        // cannot mint another.
        if (adoptIssuedGameToken(started)) {
            EXCHANGING.set(false);
            return;
        }
        String launcher = launcherToken();
        if (launcher == null) {
            state = AuthState.NOT_SIGNED_IN;
            nextAttemptAt = started + NO_CREDENTIAL_RETRY_MS;
            EXCHANGING.set(false);
            return;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(uri("/auth/game-session"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + launcher)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
                try {
                    handleExchange(res, err);
                } catch (Throwable t) {
                    state = AuthState.UNREACHABLE;
                    nextAttemptAt = System.currentTimeMillis() + FAILURE_RETRY_MS;
                    Log.warn("[Breeze] game token response unreadable ({})", t.getClass().getSimpleName());
                } finally {
                    EXCHANGING.set(false);
                }
            });
        } catch (Throwable t) {
            state = AuthState.UNREACHABLE;
            nextAttemptAt = started + FAILURE_RETRY_MS;
            EXCHANGING.set(false);
            Log.warn("[Breeze] game token request failed: {}", t.toString());
        }
    }

    private static void handleExchange(HttpResponse<String> res, Throwable err) {
        long now = System.currentTimeMillis();
        if (err != null || res == null) {
            state = AuthState.UNREACHABLE;
            nextAttemptAt = now + FAILURE_RETRY_MS;
            Log.warn("[Breeze] could not reach {} for a game token: {}",
                    baseUrl(), err != null ? err.toString() : "no response");
            return;
        }
        int code = res.statusCode();
        if (code == 401 || code == 403) {
            state = AuthState.NOT_SIGNED_IN;
            nextAttemptAt = now + REJECTED_RETRY_MS;
            Log.warn("[Breeze] launcher session was rejected (HTTP {}); sign in again from the launcher", code);
            return;
        }
        if (code == 429) {
            state = AuthState.UNREACHABLE;
            nextAttemptAt = now + REJECTED_RETRY_MS;
            Log.warn("[Breeze] game token request was rate limited; retrying later");
            return;
        }
        if (code != 200) {
            state = AuthState.UNREACHABLE;
            nextAttemptAt = now + FAILURE_RETRY_MS;
            Log.warn("[Breeze] game token request failed: HTTP {}", code);
            return;
        }

        JsonObject root = GSON.fromJson(res.body(), JsonObject.class);
        JsonElement t = root == null ? null : root.get("token");
        boolean success = root != null && root.has("success") && root.get("success").getAsBoolean();
        if (!success || t == null || t.isJsonNull() || t.getAsString().isBlank()) {
            state = AuthState.UNREACHABLE;
            nextAttemptAt = now + FAILURE_RETRY_MS;
            Log.warn("[Breeze] game token response did not contain a token");
            return;
        }

        long lifetime = ASSUMED_LIFETIME_MS;
        JsonElement exp = root.get("expiresAt");
        if (exp != null && !exp.isJsonNull()) {
            try {
                long parsed = Instant.parse(exp.getAsString()).toEpochMilli() - now;
                // The server issued this token a moment ago, so an expiry that
                // is already past or nearly so means the local clock disagrees
                // with the server's. Trusting it would re-exchange on every
                // call; the assumed lifetime is used instead and a 401 corrects
                // it if the token really does lapse sooner.
                if (parsed > 2 * REFRESH_MARGIN_MS) lifetime = parsed;
            } catch (Throwable ignored) {}
        }
        synchronized (LOCK) {
            token = t.getAsString();
            tokenObtainedAt = now;
            expiresAt = now + lifetime;
            refreshAt = expiresAt - REFRESH_MARGIN_MS;
            // Floor on how often a success can be followed by another exchange,
            // whatever goes wrong afterwards. The auth limiter is shared with
            // the launcher.
            nextAttemptAt = now + MIN_EXCHANGE_GAP_MS;
            state = AuthState.READY;
        }
        Log.info("[Breeze] obtained a game token, valid for {} minutes", lifetime / 60_000L);
    }
}
