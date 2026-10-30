package dev.breeze.cape;

import dev.breeze.compat.Ids;
import dev.breeze.cosmetics.CapePolicy;
import dev.breeze.cosmetics.TextureNames;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.mojang.blaze3d.platform.NativeImage;
import dev.breeze.compat.Images;
import dev.breeze.net.BreezeApi;
import dev.breeze.net.BreezePresence;
import dev.breeze.net.Self;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ServerCapes {

    private static final Gson GSON = new Gson();
    private static final List<String> OWNED = new CopyOnWriteArrayList<>();
    private static final Map<String, ResourceLocation> READY = new ConcurrentHashMap<>();
    private static final Map<String, byte[]> BYTES = new ConcurrentHashMap<>();
    private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();
    /**
     * When each cape's image last failed to download. Asked again after
     * {@link #RETRY_MS}: these images are shared by every player wearing the
     * cape, so one lost request must not hide it for the whole session.
     */
    private static final Map<String, Long> MISSING = new ConcurrentHashMap<>();
    private static final long RETRY_MS = 60_000;
    private static volatile boolean ownedLoaded;
    private static volatile boolean ownedRequested;
    /**
     * When the selected cape was last asked for, 0 for never. Asked again
     * every {@link #SELECTION_REFRESH_MS}: this route is how your own cape is
     * found when the cosmetic state cannot be used, and a cape equipped in the
     * launcher mid-game has to show up without a restart.
     */
    private static volatile long selectionAskedAt;
    private static final long SELECTION_REFRESH_MS = 15_000;
    private static volatile String selected = "";

    private ServerCapes() {}

    private static HttpClient http() {
        return BreezeApi.http();
    }

    public static List<String> owned() {
        return OWNED;
    }

    public static boolean ownedLoaded() {
        return ownedLoaded;
    }

    public static String selected() {
        return selected;
    }

    public static void refresh() {
        ownedRequested = false;
        ownedLoaded = false;
        MISSING.clear();
        fetchOwned();
    }

    public static void fetchOwned() {
        if (ownedRequested) return;
        ownedRequested = true;
        Minecraft mc = Minecraft.getInstance();
        UUID me = Self.selfUuid();
        if (!BreezePresence.enabled() || mc.player == null || me == null) {
            ownedLoaded = true;
            return;
        }
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(BreezeApi.uri("/capes/" + me))
                    .timeout(Duration.ofSeconds(5)).GET();
            // Public route: the token is sent when there is one, and the
            // request goes out either way.
            BreezeApi.authed(b);
            http().sendAsync(b.build(), HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
                ownedLoaded = true;
                if (err != null || res == null || res.statusCode() != 200) return;
                try {
                    JsonArray arr = GSON.fromJson(res.body(), JsonArray.class);
                    if (arr == null) return;
                    OWNED.clear();
                    // Catalogue capes only. The API also lists files from its
                    // test folder (mod_capes), which are not players' capes.
                    for (JsonElement e : arr) {
                        String name = e.getAsString().trim();
                        if (CapePolicy.legacySource(name) == CapePolicy.LegacySource.CATALOGUE) OWNED.add(name);
                    }
                } catch (Throwable ignored) {}
            });
        } catch (Throwable t) {
            ownedLoaded = true;
        }
    }

    public static void ensureSelected() {
        long now = System.currentTimeMillis();
        if (selectionAskedAt != 0 && now - selectionAskedAt < SELECTION_REFRESH_MS) return;
        selectionAskedAt = now;
        Minecraft mc = Minecraft.getInstance();
        UUID me = Self.selfUuid();
        if (!BreezePresence.enabled() || mc.player == null || me == null) return;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(BreezeApi.uri("/selected/" + me))
                    .timeout(Duration.ofSeconds(5)).GET();
            BreezeApi.authed(b);
            http().sendAsync(b.build(), HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
                if (err != null || res == null) return;
                if (res.statusCode() == 200) selected = res.body().trim();
                // 404 is "nothing equipped" (taken off in the launcher).
                else if (res.statusCode() == 404) selected = "";
            });
        } catch (Throwable ignored) {}
    }

    /**
     * A change the Wardrobe has already sent (CosmeticActions): your own cape
     * follows at once, and the next answer from /selected confirms it.
     */
    public static void applied(String name) {
        selected = name == null || name.equalsIgnoreCase("none") ? "" : name;
        selectionAskedAt = System.currentTimeMillis();
    }

    public static void select(String name) {
        boolean clear = name == null || name.isEmpty() || name.equalsIgnoreCase("none");
        selected = clear ? "" : name;
        Minecraft mc = Minecraft.getInstance();
        UUID me = Self.selfUuid();
        if (!BreezePresence.enabled() || mc.player == null || me == null) return;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(BreezeApi.uri("/select/" + me))
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(clear ? "none" : name));
            if (!BreezeApi.authed(b)) {
                // The write cannot go out, so re-read what the server holds
                // rather than keep showing a selection that never landed.
                selectionAskedAt = 0;
                return;
            }
            http().sendAsync(b.build(), HttpResponse.BodyHandlers.discarding()).whenComplete((res, err) -> {
                if (err == null && res != null && res.statusCode() >= 200 && res.statusCode() < 300) return;
                // Only 401 is handed back as a token problem. This route also
                // answers 403 for "not owned", which a new token cannot fix.
                if (res != null && res.statusCode() == 401) BreezeApi.onUnauthorized(401);
                selectionAskedAt = 0;
            });
        } catch (Throwable ignored) {}
    }

    /**
     * Your own cape from the older routes: a catalogue cape by its id, or your
     * uploaded (personal) cape from /cape/:uuid. Anything else the selection
     * route answers is not a cape in the account database and is not drawn.
     */
    public static ResourceLocation selfTexture() {
        ensureSelected();
        String name = selected;
        return switch (CapePolicy.legacySource(name)) {
            case CATALOGUE -> textureFor(name.trim());
            case PERSONAL -> RemoteCapes.personalTexture(Self.selfUuid());
            case NONE -> null;
        };
    }

    /** A catalogue cape's image by its id (/capefile/<id>), shared by everyone wearing it. */
    public static ResourceLocation textureFor(String name) {
        ResourceLocation ready = READY.get(name);
        if (ready != null) return ready;
        byte[] data = BYTES.remove(name);
        if (data != null) {
            ResourceLocation rl = register(name, data);
            if (rl != null) {
                READY.put(name, rl);
                return rl;
            }
            MISSING.put(name, System.currentTimeMillis());
            return null;
        }
        fetchFile(name);
        return null;
    }

    private static void fetchFile(String name) {
        Long failedAt = MISSING.get(name);
        if (failedAt != null && System.currentTimeMillis() - failedAt < RETRY_MS) return;
        if (!PENDING.add(name)) return;
        if (!BreezePresence.enabled()) {
            PENDING.remove(name);
            return;
        }
        try {
            // The name comes from the server's owned list and is concatenated
            // into a path. Encoded, a name containing a slash, '?' or '#' stays
            // one path segment instead of reaching a different route or query.
            // URLEncoder is for forms, so its '+' for a space becomes %20.
            String segment = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
            HttpRequest.Builder b = HttpRequest.newBuilder(BreezeApi.uri("/capefile/" + segment))
                    .timeout(Duration.ofSeconds(6)).GET();
            BreezeApi.authed(b);
            http().sendAsync(b.build(), HttpResponse.BodyHandlers.ofByteArray()).whenComplete((res, err) -> {
                PENDING.remove(name);
                if (err != null || res == null || res.statusCode() != 200 || res.body().length == 0) {
                    MISSING.put(name, System.currentTimeMillis());
                    return;
                }
                MISSING.remove(name);
                BYTES.put(name, res.body());
            });
        } catch (Throwable t) {
            PENDING.remove(name);
        }
    }

    private static ResourceLocation register(String name, byte[] data) {
        try {
            NativeImage img;
            try {
                img = NativeImage.read(new ByteArrayInputStream(data));
            } catch (Throwable notPng) {
                BufferedImage bi = ImageIO.read(new ByteArrayInputStream(data));
                if (bi == null) return null;
                img = new NativeImage(bi.getWidth(), bi.getHeight(), true);
                for (int y = 0; y < bi.getHeight(); y++) {
                    for (int x = 0; x < bi.getWidth(); x++) {
                        Images.setPixelArgb(img, x, y, bi.getRGB(x, y));
                    }
                }
            }
            // Cape names can hold spaces and capitals, which a resource id refuses.
            ResourceLocation rl = Ids.breeze("server_capes/" + TextureNames.segment(name));
            Images.register(rl, img);
            return rl;
        } catch (Throwable t) {
            return null;
        }
    }
}
