package dev.breeze.cosmetics;

import dev.breeze.net.BreezeApi;
import dev.breeze.net.BreezePresence;
import dev.breeze.net.Self;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Writes cosmetic changes back to the API from inside the game.
 *
 * The read side is {@link CosmeticState}; this is the write side. The routes
 * keep the player's uuid in the path, but since v1.0.22 that uuid is no longer
 * trusted on its own: every write carries the game token from
 * {@link BreezeApi}, and the API refuses it unless the token speaks for that
 * same uuid. The game token is narrower than the launcher's account token, so
 * the account routes the launcher uses stay out of reach from inside the game.
 * Ownership of the cape or tag is still enforced server-side on top of that.
 *
 * Every call invalidates the local state cache on completion, so the next poll
 * happens immediately rather than waiting out the refresh interval. That is
 * what makes a change in the Wardrobe show up in game at once instead of
 * seconds later.
 */
public final class CosmeticActions {

    private CosmeticActions() {}

    /**
     * Equip a cape, or unequip when {@code capeId} is null.
     *
     * The callback runs on an HTTP thread, not the render thread. Callers that
     * touch anything render-related must hop back via {@code Minecraft.execute}.
     */
    public static void equipCape(String capeId, Consumer<Boolean> done) {
        post("/select/", capeId == null ? "none" : capeId, done);
    }

    /** Equip a tag, or return to the automatic highest-priority choice with null. */
    public static void equipTag(String tagId, Consumer<Boolean> done) {
        post("/cosmetics/tag/", tagId == null ? "none" : tagId, done);
    }

    private static void post(String path, String body, Consumer<Boolean> done) {
        // The account's uuid, which is what the token is checked against. The
        // in-world id differs on an offline-mode server and would be refused.
        // No world is needed: the Wardrobe works from the title menu, and the
        // server identifies the player by the game token, not a loaded player.
        UUID id = Self.selfUuid();
        if (id == null || !BreezePresence.enabled()) {
            if (done != null) done.accept(false);
            return;
        }

        try {
            HttpRequest.Builder b = HttpRequest
                    .newBuilder(BreezeApi.uri(path + id))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (!BreezeApi.authed(b)) {
                // Not signed in yet. Reported as a failed change, so the
                // Wardrobe clears its busy state instead of waiting forever.
                if (done != null) done.accept(false);
                return;
            }

            http().sendAsync(b.build(), HttpResponse.BodyHandlers.ofString())
                    .whenComplete((res, err) -> {
                        boolean okay = err == null && res != null
                                && res.statusCode() >= 200 && res.statusCode() < 300;
                        // Only 401 is a token problem here. These routes also
                        // answer 403 when the player does not own the cape or
                        // tag, which a fresh token would not change.
                        if (res != null && res.statusCode() == 401) BreezeApi.onUnauthorized(401);
                        // Refresh regardless of the result. On success the new
                        // state is what we want; on failure a re-read is how the
                        // UI corrects itself back to what the server actually
                        // holds, rather than showing a change that did not land.
                        // The state cache is keyed by the in-world id, which is
                        // only the same as the account id on an online server.
                        CosmeticState.invalidate(id);
                        CosmeticState.invalidateSelf();
                        if (done != null) done.accept(okay);
                    });
        } catch (Throwable t) {
            if (done != null) done.accept(false);
        }
    }

    private static HttpClient http() {
        return BreezeApi.http();
    }
}
