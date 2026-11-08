package dev.breeze.cape;

import dev.breeze.cosmetics.CapePolicy;
import dev.breeze.cosmetics.CapeTextures;
import dev.breeze.cosmetics.CosmeticState;
import dev.breeze.compat.Ids;
import com.mojang.blaze3d.platform.NativeImage;
import dev.breeze.compat.Images;
import dev.breeze.BreezeUsers;
import dev.breeze.modules.CustomCape;
import dev.breeze.net.BreezeApi;
import dev.breeze.net.BreezePresence;
import dev.breeze.net.Self;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RemoteCapes {

    /** Each player's answer from /selected/:uuid: a catalogue cape id, "personal", or none. */
    private static final Map<UUID, String> SELECTED = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> SELECTION_ASKED_AT = new ConcurrentHashMap<>();
    private static final Set<UUID> SELECTION_PENDING = ConcurrentHashMap.newKeySet();

    // Personal capes (the player's own upload), one image per player from /cape/:uuid.
    private static final Map<UUID, ResourceLocation> READY = new ConcurrentHashMap<>();
    private static final Map<UUID, byte[]> DOWNLOADED = new ConcurrentHashMap<>();
    private static final Set<UUID> PENDING = ConcurrentHashMap.newKeySet();
    /** When each player's personal cape was last asked for, answered or not. */
    private static final Map<UUID, Long> ASKED_AT = new ConcurrentHashMap<>();
    /** A fingerprint of each player's last image, so an unchanged one is not uploaded again. */
    private static final Map<UUID, Integer> LAST_IMAGE = new ConcurrentHashMap<>();
    /** Players whose last answer was "no cape". */
    private static final Set<UUID> MISSING = ConcurrentHashMap.newKeySet();
    /**
     * How often another player's cape is asked for again on these routes. They
     * are the only way capes show while the cosmetic state cannot be used, so a
     * cape put on or taken off mid-game has to come through without a restart.
     */
    private static final long REFRESH_MS = 60_000;

    private RemoteCapes() {}

    /**
     * The Breeze cape to draw on a player, or null to leave the game's own.
     * Called for every visible player every frame, so nothing here waits: each
     * lookup answers from cache and starts a download when it has nothing yet.
     *
     * Which source wins is decided by {@link CapePolicy}. The unified cosmetic
     * state is the truth once loaded, and your own equipped cape no longer
     * needs the Custom Cape module on.
     */
    public static ResourceLocation capeFor(UUID id) {
        if (id == null) return null;
        Minecraft mc = Minecraft.getInstance();
        boolean self = mc.player != null && id.equals(mc.player.getUUID());

        // Your own cape follows your account, which is what the Wardrobe
        // equips. On an offline-mode server the in-world id is a different one.
        UUID account = self ? Self.selfUuid() : null;
        CosmeticState.Entry state = CosmeticState.get(account != null ? account : id);
        // One read of each field, so a refresh landing mid-frame cannot pair
        // "equipped" with a cape that has just been cleared. A state that could
        // not read the player's account is not the truth (CapePolicy.trustState).
        boolean loaded = CapePolicy.trustState(state.loaded, state.username);
        CosmeticState.CapeInfo cape = state.cape;

        return CapePolicy.pick(self, CustomCape.active(), new CapePolicy.Lookup<ResourceLocation>() {
            @Override public boolean stateLoaded() { return loaded; }
            @Override public boolean capeEquipped() { return cape != null; }
            @Override public ResourceLocation equippedTexture() { return CapeTextures.current(cape); }
            @Override public boolean equippedFailed() { return CapeTextures.failed(cape); }
            @Override public ResourceLocation legacySelfTexture() { return ServerCapes.selfTexture(); }
            @Override public ResourceLocation localTexture() { return CapeManager.current(); }
            @Override public boolean breezeUser() { return BreezeUsers.isBreezeUser(id); }
            @Override public ResourceLocation legacyPlayerTexture() { return legacyTexture(id); }
        });
    }

    /**
     * Another player's cape from the older routes. /selected/:uuid names it;
     * a catalogue cape's image then comes from /capefile/<id>, downloaded once
     * for everyone wearing it, and a personal one from /cape/:uuid. A name that
     * is not a cape in the account database (the API's folder of test images)
     * is not drawn.
     */
    private static ResourceLocation legacyTexture(UUID id) {
        Long asked = SELECTION_ASKED_AT.get(id);
        if (asked == null || System.currentTimeMillis() - asked >= REFRESH_MS) askSelection(id);
        String selected = SELECTED.get(id);
        return switch (CapePolicy.legacySource(selected)) {
            case CATALOGUE -> ServerCapes.textureFor(selected.trim());
            case PERSONAL -> personalTexture(id);
            case NONE -> null;
        };
    }

    private static void askSelection(UUID id) {
        if (!SELECTION_PENDING.add(id)) return;
        SELECTION_ASKED_AT.put(id, System.currentTimeMillis());
        if (!BreezePresence.enabled()) {
            SELECTION_PENDING.remove(id);
            return;
        }
        try {
            // In-world id on purpose: this is another player as the current
            // server knows them. Public route, so the token is sent when there
            // is one and the request goes out either way.
            HttpRequest.Builder b = HttpRequest.newBuilder(BreezeApi.uri("/selected/" + id))
                    .timeout(Duration.ofSeconds(5)).GET();
            BreezeApi.authed(b);
            BreezeApi.http().sendAsync(b.build(), HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
                SELECTION_PENDING.remove(id);
                if (err != null || res == null) return; // keep the last answer; asked again later
                if (res.statusCode() == 200) SELECTED.put(id, res.body().trim());
                // 404 is "nothing equipped"; anything else (a limit, an error)
                // keeps the last answer.
                else if (res.statusCode() == 404) SELECTED.remove(id);
            });
        } catch (Throwable t) {
            SELECTION_PENDING.remove(id);
        }
    }

    /**
     * A personal cape (the player's own upload, kept in Supabase Storage) from
     * /cape/:uuid, which the API serves from the account's users.cape_url.
     * Asked again every {@link #REFRESH_MS}. Null for a null id.
     */
    public static ResourceLocation personalTexture(UUID id) {
        if (id == null) return null;
        // New bytes first: a refresh replaces the texture under the same id.
        byte[] data = DOWNLOADED.remove(id);
        if (data != null) {
            ResourceLocation rl = registerTexture(id, data);
            if (rl != null) READY.put(id, rl);
            else {
                READY.remove(id);
                MISSING.add(id);
            }
        }
        Long asked = ASKED_AT.get(id);
        if (asked == null || System.currentTimeMillis() - asked >= REFRESH_MS) fetch(id);
        return MISSING.contains(id) ? null : READY.get(id);
    }

    private static void fetch(UUID id) {
        if (!PENDING.add(id)) return;
        ASKED_AT.put(id, System.currentTimeMillis());
        if (!BreezePresence.enabled()) {
            PENDING.remove(id);
            return;
        }
        try {
            // Public route, so the token is sent when there is one and the
            // request goes out either way.
            HttpRequest.Builder b = HttpRequest.newBuilder(BreezeApi.uri("/cape/" + id))
                    .timeout(Duration.ofSeconds(6)).GET();
            BreezeApi.authed(b);
            BreezeApi.http().sendAsync(b.build(), HttpResponse.BodyHandlers.ofByteArray()).whenComplete((res, err) -> {
                PENDING.remove(id);
                if (err != null || res == null) return; // keep what is shown; asked again later
                if (res.statusCode() != 200 || res.body().length == 0) {
                    MISSING.add(id);
                    return;
                }
                MISSING.remove(id);
                int fingerprint = java.util.Arrays.hashCode(res.body());
                Integer before = LAST_IMAGE.put(id, fingerprint);
                if (before != null && before == fingerprint && READY.containsKey(id)) return;
                DOWNLOADED.put(id, res.body());
            });
        } catch (Throwable t) {
            PENDING.remove(id);
        }
    }

    private static ResourceLocation registerTexture(UUID id, byte[] data) {
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
            ResourceLocation rl = Ids.breeze("remote_capes/" + id.toString().replace("-", ""));
            Images.register(rl, img);
            return rl;
        } catch (Throwable t) {
            return null;
        }
    }
}
