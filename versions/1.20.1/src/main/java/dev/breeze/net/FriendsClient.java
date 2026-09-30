package dev.breeze.net;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class FriendsClient {

    public static final class Entry {
        public final UUID id;
        public final String name;
        public final boolean online;

        public Entry(UUID id, String name, boolean online) {
            this.id = id;
            this.name = name;
            this.online = online;
        }
    }

    public static final class Msg {
        public final long ts;
        public final boolean mine;
        public final String fromName;
        public final String text;

        public Msg(long ts, boolean mine, String fromName, String text) {
            this.ts = ts;
            this.mine = mine;
            this.fromName = fromName;
            this.text = text;
        }
    }

    private static final Gson GSON = new Gson();
    // Status lines this class writes on its own, as opposed to a server reply
    // to something the player just did. Only these are cleared or replaced by
    // background polls, so a "request sent" message is not wiped a tick later.
    private static final String STATUS_UNREACHABLE = "Can't reach the Breeze server.";
    private static final String STATUS_SIGNED_OUT = "Not signed in to Breeze. Start the game from the Breeze launcher.";
    private static final String STATUS_SIGNING_IN = "Signing in to Breeze...";
    private static final String STATUS_REJECTED = "Breeze did not accept this session. Signing in again...";
    public static final List<Entry> FRIENDS = new CopyOnWriteArrayList<>();
    public static final List<Entry> INCOMING = new CopyOnWriteArrayList<>();
    public static final List<Entry> OUTGOING = new CopyOnWriteArrayList<>();
    public static final Map<UUID, List<Msg>> HISTORY = new ConcurrentHashMap<>();
    private static final Set<UUID> KNOWN_INCOMING = ConcurrentHashMap.newKeySet();
    public static volatile String status = "";
    /** When the friends list last arrived from the API, epoch millis; 0 before the first. */
    public static volatile long updatedAt;
    private static volatile boolean lastFailed;
    private static long lastList;
    private static long lastDm;
    private static long dmSince;

    private FriendsClient() {}

    private static HttpClient http() {
        return BreezeApi.http();
    }

    private static boolean ownStatus(String s) {
        return s.isEmpty() || s.equals(STATUS_UNREACHABLE) || s.equals(STATUS_SIGNED_OUT)
                || s.equals(STATUS_SIGNING_IN) || s.equals(STATUS_REJECTED);
    }

    /**
     * Put the reason no request can go out into the status line.
     *
     * "Not signed in" and "cannot reach the server" need different fixes from
     * the player (relaunch from the launcher, or check their connection), so
     * they are never reported as the same thing.
     */
    private static void reportNoToken(boolean force) {
        String why;
        switch (BreezeApi.authState()) {
            case NOT_SIGNED_IN: why = STATUS_SIGNED_OUT; break;
            case UNREACHABLE: why = STATUS_UNREACHABLE; break;
            default: why = STATUS_SIGNING_IN; break;
        }
        if (force || ownStatus(status)) status = why;
    }

    /** Status text for a failed call, and a rejected token handed back to BreezeApi. */
    private static String failure(Throwable err, HttpResponse<?> res) {
        if (err != null || res == null) return STATUS_UNREACHABLE;
        int code = res.statusCode();
        BreezeApi.onUnauthorized(code);
        return code == 401 || code == 403 ? STATUS_REJECTED : STATUS_UNREACHABLE;
    }

    public static void tick(Minecraft mc) {
        if (!BreezePresence.enabled() || Self.selfUuid() == null) return;
        if (BreezeApi.gameToken() == null) {
            // Polls wait for a token instead of collecting 401s; the timers are
            // left alone so they run on the first tick that has one.
            reportNoToken(false);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastList > (lastFailed ? 5000L : 20000L)) {
            lastList = now;
            refresh(mc);
        }
        if (now - lastDm > 5000L) {
            lastDm = now;
            pollDms(mc);
        }
    }

    private static void fill(List<Entry> target, JsonObject root, String key) {
        try {
            if (!root.has(key)) return;
            JsonArray arr = root.getAsJsonArray(key);
            for (JsonElement e : arr) {
                JsonObject o = e.getAsJsonObject();
                UUID id = UUID.fromString(o.get("uuid").getAsString());
                String name = o.has("name") ? o.get("name").getAsString() : "?";
                boolean online = o.has("online") && o.get("online").getAsBoolean();
                target.add(new Entry(id, name, online));
            }
        } catch (Throwable ignored) {}
    }

    public static void refresh(Minecraft mc) {
        UUID me = Self.selfUuid();
        if (!BreezePresence.enabled() || me == null) return;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(BreezeApi.uri("/friends/list/" + me))
                    .timeout(Duration.ofSeconds(10)).GET();
            if (!BreezeApi.authed(b)) {
                reportNoToken(false);
                return;
            }
            http().sendAsync(b.build(), HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
                try {
                    if (err != null || res == null || res.statusCode() != 200) {
                        lastFailed = true;
                        String why = failure(err, res);
                        if (ownStatus(status)) status = why;
                        dev.breeze.BreezeClient.LOGGER.warn("[Breeze] friends refresh failed: {}",
                                err != null ? err.toString() : "HTTP " + (res == null ? "no response" : res.statusCode()));
                        return;
                    }
                    JsonObject root = GSON.fromJson(res.body(), JsonObject.class);
                    if (root == null) return;
                    lastFailed = false;
                    if (ownStatus(status)) status = "";
                    List<Entry> fr = new ArrayList<>();
                    List<Entry> in = new ArrayList<>();
                    List<Entry> out = new ArrayList<>();
                    fill(fr, root, "friends");
                    fill(in, root, "incoming");
                    fill(out, root, "outgoing");
                    FRIENDS.clear();
                    FRIENDS.addAll(fr);
                    OUTGOING.clear();
                    OUTGOING.addAll(out);
                    for (Entry e : in) {
                        if (KNOWN_INCOMING.add(e.id)) {
                            chat(mc, e.name + " sent you a friend request. Open Breeze (Right Shift) and go to Friends to accept.");
                        }
                    }
                    INCOMING.clear();
                    INCOMING.addAll(in);
                    updatedAt = System.currentTimeMillis();
                    for (Entry e : fr) {
                        dev.breeze.Friends.add(e.id);
                    }
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    private static void pollDms(Minecraft mc) {
        UUID me = Self.selfUuid();
        if (!BreezePresence.enabled() || me == null) return;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(BreezeApi.uri("/dm/" + me + "?since=" + dmSince))
                    .timeout(Duration.ofSeconds(10)).GET();
            if (!BreezeApi.authed(b)) return;
            http().sendAsync(b.build(), HttpResponse.BodyHandlers.ofString()).thenAccept(res -> {
                try {
                    if (res.statusCode() != 200) {
                        // Not logged: this runs every five seconds and the
                        // friends refresh already logs the same failure.
                        String why = failure(null, res);
                        if (ownStatus(status)) status = why;
                        return;
                    }
                    JsonArray arr = GSON.fromJson(res.body(), JsonArray.class);
                    if (arr == null) return;
                    for (JsonElement e : arr) {
                        JsonObject o = e.getAsJsonObject();
                        long ts = o.get("ts").getAsLong();
                        UUID from = UUID.fromString(o.get("from").getAsString());
                        UUID to = UUID.fromString(o.get("to").getAsString());
                        String fromName = o.has("fromName") ? o.get("fromName").getAsString() : "?";
                        String text = o.get("text").getAsString();
                        if (ts > dmSince) dmSince = ts;
                        boolean mine = from.equals(me);
                        UUID bucket = mine ? to : from;
                        if (mine && hasLocal(bucket, ts, text)) continue;
                        history(bucket).add(new Msg(ts, mine, mine ? "You" : fromName, text));
                        trim(bucket);
                        if (!mine) {
                            chat(mc, "[DM] " + fromName + ": " + text);
                        }
                    }
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    private static boolean hasLocal(UUID bucket, long ts, String text) {
        List<Msg> list = HISTORY.get(bucket);
        if (list == null) return false;
        for (int i = list.size() - 1; i >= 0 && i >= list.size() - 10; i--) {
            Msg m = list.get(i);
            if (m.mine && m.text.equals(text) && Math.abs(m.ts - ts) < 15000L) return true;
        }
        return false;
    }

    private static List<Msg> history(UUID id) {
        return HISTORY.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>());
    }

    private static void trim(UUID id) {
        List<Msg> list = HISTORY.get(id);
        while (list != null && list.size() > 100) {
            list.remove(0);
        }
    }

    public static void requestFriend(Minecraft mc, String name) {
        UUID me = Self.selfUuid();
        if (!BreezePresence.enabled() || me == null || name == null || name.isEmpty()) return;
        status = "sending...";
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(BreezeApi.uri("/friends/request/" + me))
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(name));
            if (!BreezeApi.authed(b)) {
                reportNoToken(true);
                return;
            }
            http().sendAsync(b.build(), HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
                if (err != null || res == null) {
                    status = STATUS_UNREACHABLE;
                    return;
                }
                int code = res.statusCode();
                if (code == 401 || code == 403) {
                    status = failure(null, res);
                    return;
                }
                // Anything else carries the API's own sentence ("request sent
                // to X", "unknown player"), which is what the player needs.
                status = res.body();
                refresh(mc);
            });
        } catch (Throwable t) {
            status = "failed";
        }
    }

    private static void simplePost(Minecraft mc, String pathPart, String body) {
        UUID me = Self.selfUuid();
        if (!BreezePresence.enabled() || me == null) return;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(BreezeApi.uri(pathPart + me))
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (!BreezeApi.authed(b)) {
                reportNoToken(true);
                return;
            }
            http().sendAsync(b.build(), HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
                if (err != null || res == null || res.statusCode() != 200) {
                    int code = res == null ? 0 : res.statusCode();
                    // A 404 or 400 is the API's own sentence, such as accepting
                    // a request that was already withdrawn, not a lost
                    // connection, so it is shown as it came.
                    status = err == null && res != null && code != 401 && code != 403 && code < 500
                            ? res.body() : failure(err, res);
                    dev.breeze.BreezeClient.LOGGER.warn("[Breeze] friends {} failed: {}", pathPart,
                            err != null ? err.toString() : "HTTP " + code);
                }
                // Refresh either way: on failure it is what corrects the lists
                // back to what the server actually holds.
                refresh(mc);
            });
        } catch (Throwable ignored) {}
    }

    public static void accept(Minecraft mc, UUID other) {
        simplePost(mc, "/friends/accept/", other.toString());
    }

    public static void deny(Minecraft mc, UUID other) {
        simplePost(mc, "/friends/deny/", other.toString());
    }

    public static void remove(Minecraft mc, UUID other) {
        simplePost(mc, "/friends/remove/", other.toString());
        HISTORY.remove(other);
    }

    public static void sendDm(Minecraft mc, UUID to, String text) {
        UUID me = Self.selfUuid();
        if (!BreezePresence.enabled() || me == null || to == null || text == null || text.isEmpty()) return;
        HttpRequest.Builder b;
        try {
            b = HttpRequest.newBuilder(BreezeApi.uri("/dm/" + me))
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(to + " " + text));
        } catch (Throwable t) {
            return;
        }
        // Checked before the message is added locally, so a message that was
        // never sent does not sit in the history looking delivered.
        if (!BreezeApi.authed(b)) {
            reportNoToken(true);
            return;
        }
        history(to).add(new Msg(System.currentTimeMillis(), true, "You", text));
        trim(to);
        try {
            http().sendAsync(b.build(), HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
                if (err == null && res != null && res.statusCode() == 200) return;
                if (err == null && res != null && res.statusCode() == 403 && "not friends".equals(res.body().trim())) {
                    // An ordinary refusal that shares 403 with the identity
                    // check. Not a token problem, so the token is kept.
                    status = "Message not delivered: you can only message friends.";
                    return;
                }
                status = "Message not delivered. " + failure(err, res);
                dev.breeze.BreezeClient.LOGGER.warn("[Breeze] dm send failed: {}",
                        err != null ? err.toString() : "HTTP " + (res == null ? "no response" : res.statusCode()));
            });
        } catch (Throwable ignored) {}
    }

    private static void chat(Minecraft mc, String s) {
        try {
            if (mc.player != null) mc.player.displayClientMessage(Component.literal("§b[Breeze]§r " + s), false);
        } catch (Throwable ignored) {}
    }
}
