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

    private static final Map<UUID, ResourceLocation> READY = new ConcurrentHashMap<>();
    private static final Map<UUID, byte[]> DOWNLOADED = new ConcurrentHashMap<>();
    private static final Set<UUID> PENDING = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> MISSING = ConcurrentHashMap.newKeySet();

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
        // "equipped" with a cape that has just been cleared.
        boolean loaded = state.loaded;
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

    /** Another player's cape from the older per-player route (/cape/:uuid). */
    private static ResourceLocation legacyTexture(UUID id) {
        ResourceLocation ready = READY.get(id);
        if (ready != null) return ready;
        byte[] data = DOWNLOADED.remove(id);
        if (data != null) {
            ResourceLocation rl = registerTexture(id, data);
            if (rl != null) {
                READY.put(id, rl);
                return rl;
            }
            MISSING.add(id);
            return null;
        }
        fetch(id);
        return null;
    }

    private static void fetch(UUID id) {
        if (MISSING.contains(id) || !PENDING.add(id)) return;
        if (!BreezePresence.enabled()) {
            PENDING.remove(id);
            return;
        }
        try {
            // In-world id on purpose: this is another player's cape as the
            // current server knows them. Public route, so the token is sent
            // when there is one and the request goes out either way.
            HttpRequest.Builder b = HttpRequest.newBuilder(BreezeApi.uri("/cape/" + id))
                    .timeout(Duration.ofSeconds(6)).GET();
            BreezeApi.authed(b);
            BreezeApi.http().sendAsync(b.build(), HttpResponse.BodyHandlers.ofByteArray()).whenComplete((res, err) -> {
                PENDING.remove(id);
                if (err != null || res == null || res.statusCode() != 200 || res.body().length == 0) {
                    MISSING.add(id);
                    return;
                }
                DOWNLOADED.put(id, res.body());
            });
        } catch (Throwable t) {
            PENDING.remove(id);
            MISSING.add(id);
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
