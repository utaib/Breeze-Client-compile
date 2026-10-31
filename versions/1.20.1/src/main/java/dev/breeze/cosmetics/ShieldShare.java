package dev.breeze.cosmetics;

import com.google.gson.JsonObject;
import dev.breeze.modules.CustomShield;
import dev.breeze.net.BreezeApi;
import dev.breeze.net.BreezePresence;
import dev.breeze.net.Self;
import net.minecraft.client.Minecraft;

import java.awt.image.BufferedImage;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Keeps Breeze's server in step with the player's sharing choice (Custom
 * Shield): while it is on, the shield's face as the player sees it (a PNG of
 * at most 96x176, ShieldTextures.previewPng) is sent with the game token to
 * PUT /shield/:uuid; when sharing, the module or the picture goes, DELETE
 * removes it. What was last sent is remembered in breeze_shields/.shared, so
 * a restart does not send it again. Looked at every two seconds from the
 * client tick; the network work is never on the game's thread.
 */
public final class ShieldShare {

    private static final long CHECK_MS = 2_000;
    private static final long RETRY_MS = 60_000;
    /** An API without the shield routes (not uploaded yet): asked again much later. */
    private static final long OLD_API_RETRY_MS = 10 * 60_000;

    private static long nextCheck;
    private static volatile long retryAt;
    private static volatile boolean busy;
    private static volatile String status = "";
    /** Requests answered, for the self-test: sent, removed. */
    public static volatile int sent, removed;

    private ShieldShare() {}

    /** A short line on sharing, for the menus ("" when there is nothing to say). */
    public static String status() {
        return status;
    }

    public static void tick(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (busy || now < nextCheck) return;
        nextCheck = now + CHECK_MS;
        String want = CustomShield.shareKey(mc);
        String have = sharedKey(mc);
        if (want.equals(have)) {
            status = want.isEmpty() ? "" : "Shared with other Breeze players.";
            return;
        }
        if (now < retryAt || !BreezePresence.enabled()) return;
        UUID self = Self.selfUuid();
        if (self == null) return;
        HttpRequest.Builder b = HttpRequest.newBuilder(BreezeApi.uri("/shield/" + self)).timeout(Duration.ofSeconds(15));
        if (!BreezeApi.authed(b)) return;
        busy = true;
        if (want.isEmpty()) {
            send(mc, b.DELETE(), want);
            return;
        }
        // The picture is read and the face made off the game's thread; the
        // game's own shield texture (round the picture) is read here first.
        ShieldTextures.ensureBase();
        String file = CustomShield.picture();
        ShieldArt.Fit fit = CustomShield.fit();
        status = "Sharing your shield.";
        CompletableFuture.runAsync(() -> {
            try {
                BufferedImage picture = ShieldTextures.readPicture(ShieldTextures.folder(mc).resolve(file));
                byte[] png = ShieldTextures.previewPng(picture, fit);
                send(mc, b.header("Content-Type", "image/png").PUT(HttpRequest.BodyPublishers.ofByteArray(png)), want);
            } catch (Throwable t) {
                failed("Your picture could not be shared: " + (t.getMessage() == null ? "it could not be read." : t.getMessage()), RETRY_MS);
            }
        });
    }

    private static void send(Minecraft mc, HttpRequest.Builder b, String want) {
        try {
            BreezeApi.http().sendAsync(b.build(), HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
                int code = err == null && res != null ? res.statusCode() : 0;
                if (code >= 200 && code < 300) {
                    remember(mc, want);
                    if (want.isEmpty()) removed++;
                    else sent++;
                    status = want.isEmpty() ? "" : "Shared with other Breeze players.";
                    retryAt = 0;
                    busy = false;
                    CosmeticState.invalidateSelf();
                    return;
                }
                if (code == 401 || code == 403) BreezeApi.onUnauthorized(code);
                if (code == 404) {
                    failed("Breeze's server cannot keep shield pictures yet. Your shield still shows for you.", OLD_API_RETRY_MS);
                } else if (code == 400 || code == 413) {
                    failed("Breeze's server refused the picture: " + reason(res.body()), RETRY_MS);
                } else {
                    failed(want.isEmpty() ? "Your shield picture could not be removed from Breeze's server yet; trying again."
                            : "Your shield could not be shared yet; trying again.", RETRY_MS);
                }
            });
        } catch (Throwable t) {
            failed("Your shield could not be shared yet; trying again.", RETRY_MS);
        }
    }

    private static void failed(String why, long wait) {
        status = why;
        retryAt = System.currentTimeMillis() + wait;
        busy = false;
    }

    private static String reason(String body) {
        try {
            JsonObject o = new com.google.gson.Gson().fromJson(body, JsonObject.class);
            if (o.has("error") && o.get("error").isJsonPrimitive()) {
                String s = o.get("error").getAsString();
                return s.length() > 160 ? s.substring(0, 160) : s;
            }
        } catch (Throwable ignored) {}
        return "it is not a usable picture.";
    }

    private static Path sharedFile(Minecraft mc) {
        return ShieldTextures.folder(mc).resolve(".shared");
    }

    /** What this game last left on Breeze's server ("" for nothing). */
    private static String sharedKey(Minecraft mc) {
        try {
            Path f = sharedFile(mc);
            if (!Files.isRegularFile(f) || Files.size(f) > 1024) return "";
            return Files.readString(f, StandardCharsets.UTF_8).strip();
        } catch (Throwable t) {
            return "";
        }
    }

    private static void remember(Minecraft mc, String key) {
        try {
            Path f = sharedFile(mc);
            if (key.isEmpty()) Files.deleteIfExists(f);
            else {
                Files.createDirectories(f.getParent());
                Files.writeString(f, key, StandardCharsets.UTF_8);
            }
        } catch (Throwable ignored) {}
    }
}
