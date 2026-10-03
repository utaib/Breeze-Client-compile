package dev.breeze.cosmetics;

import dev.breeze.net.BreezeApi;
import dev.breeze.net.BreezePresence;
import dev.breeze.net.Self;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The 3D cosmetics this account owns, and equipping them from inside the game
 * (docs/COSMETICS.md, the three :modUuid routes). Every call carries the game
 * token; the API checks it speaks for this account and that the account owns
 * what it equips.
 *
 * An API without these routes answers 404: the state is then UNAVAILABLE and
 * the Wardrobe shows what the account wears without equip controls, as before.
 */
public final class OwnedModels {

    public enum Status { LOADING, READY, UNAVAILABLE, SIGNED_OUT, FAILED }

    private static final long REFRESH_MS = 30_000;

    private static volatile List<OwnedCosmetics.Item> items = List.of();
    private static volatile Status status = Status.LOADING;
    private static volatile long fetchedAt;
    private static volatile boolean inFlight;

    private OwnedModels() {}

    public static Status status() {
        return status;
    }

    public static List<OwnedCosmetics.Item> items() {
        return items;
    }

    public static boolean owns(String id) {
        for (OwnedCosmetics.Item i : items) if (i.id.equals(id)) return true;
        return false;
    }

    /** Asks again when the list is old (or always with force); never blocks. */
    public static void refresh(boolean force) {
        if (inFlight) return;
        if (!force && fetchedAt != 0 && System.currentTimeMillis() - fetchedAt < REFRESH_MS) return;
        UUID id = Self.selfUuid();
        if (id == null || !BreezePresence.enabled()) {
            status = Status.SIGNED_OUT;
            return;
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(BreezeApi.uri("/cosmetics/owned/" + id))
                .timeout(Duration.ofSeconds(8)).GET();
        if (!BreezeApi.authed(b)) {
            status = Status.SIGNED_OUT;
            return;
        }
        inFlight = true;
        try {
            BreezeApi.http().sendAsync(b.build(), HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
                try {
                    if (err != null || res == null) {
                        status = Status.FAILED;
                    } else if (res.statusCode() == 200) {
                        items = OwnedCosmetics.parse(res.body());
                        status = Status.READY;
                    } else if (res.statusCode() == 404) {
                        // An API from before the in-game routes.
                        status = Status.UNAVAILABLE;
                    } else if (res.statusCode() == 401 || res.statusCode() == 403) {
                        if (res.statusCode() == 401) BreezeApi.onUnauthorized(401);
                        status = Status.SIGNED_OUT;
                    } else {
                        status = Status.FAILED;
                    }
                } catch (Throwable t) {
                    status = Status.FAILED;
                } finally {
                    fetchedAt = System.currentTimeMillis();
                    inFlight = false;
                }
            });
        } catch (Throwable t) {
            status = Status.FAILED;
            fetchedAt = System.currentTimeMillis();
            inFlight = false;
        }
    }

    /** Refreshes and waits up to waitMs for the answer; for the bridge's IO thread only. */
    public static Status await(boolean force, long waitMs) {
        long before = fetchedAt;
        refresh(force);
        long until = System.currentTimeMillis() + waitMs;
        while ((inFlight || (force && fetchedAt == before && status != Status.SIGNED_OUT))
                && System.currentTimeMillis() < until) {
            try {
                Thread.sleep(40);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return status;
    }

    /** Equips an owned cosmetic in its slot. done runs on an HTTP thread. */
    public static void equip(String cosmeticId, Consumer<Boolean> done) {
        post("/cosmetics/equip/", "{\"cosmetic_id\":" + quote(cosmeticId) + "}", done);
    }

    /** Empties a slot. done runs on an HTTP thread. */
    public static void unequip(String slot, Consumer<Boolean> done) {
        post("/cosmetics/unequip/", "{\"slot\":" + quote(slot) + "}", done);
    }

    private static void post(String path, String json, Consumer<Boolean> done) {
        UUID id = Self.selfUuid();
        if (id == null || !BreezePresence.enabled()) {
            if (done != null) done.accept(false);
            return;
        }
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(BreezeApi.uri(path + id))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json));
            if (!BreezeApi.authed(b)) {
                if (done != null) done.accept(false);
                return;
            }
            BreezeApi.http().sendAsync(b.build(), HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
                boolean okay = err == null && res != null && res.statusCode() >= 200 && res.statusCode() < 300;
                if (res != null && res.statusCode() == 401) BreezeApi.onUnauthorized(401);
                // Re-read both sides whatever happened: what the account owns and
                // marks equipped, and what the renderer draws on the player.
                WornCosmetics.invalidateAccount();
                refresh(true);
                if (done != null) done.accept(okay);
            });
        } catch (Throwable t) {
            if (done != null) done.accept(false);
        }
    }

    /** A JSON string literal; ids and slots are short API values, but quote them properly anyway. */
    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }
}
