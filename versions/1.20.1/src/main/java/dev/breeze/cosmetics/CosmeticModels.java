package dev.breeze.cosmetics;

import com.mojang.blaze3d.platform.NativeImage;
import dev.breeze.BreezeClient;
import dev.breeze.compat.Images;
import dev.breeze.cosmetics.model.GlbModel;
import dev.breeze.cosmetics.model.GlbReader;
import dev.breeze.cosmetics.model.Pose;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 3D cosmetic models, loaded once per cosmetic id: the GLB downloaded (only
 * from the Breeze API's own host, as cape images), read and measured off the
 * render thread, then its images uploaded as textures on the render thread.
 * A model that fails is not fetched again until the game restarts.
 */
public final class CosmeticModels {

    /** A model ready to draw. */
    public static final class Loaded {
        public final GlbModel model;
        /** Rest bounds, from the API's metadata or measured here. */
        public final float[] bounds;
        /** One texture per image in the model; the white texture where an image did not decode. */
        public final ResourceLocation[] images;

        Loaded(GlbModel model, float[] bounds, ResourceLocation[] images) {
            this.model = model;
            this.bounds = bounds;
            this.images = images;
        }
    }

    private static final Map<String, Loaded> READY = new ConcurrentHashMap<>();
    private static final Set<String> LOADING = ConcurrentHashMap.newKeySet();
    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();
    private static final Map<String, List<ResourceLocation>> TEXTURES = new ConcurrentHashMap<>();
    private static ResourceLocation white;

    private CosmeticModels() {}

    /** The model for a worn cosmetic, or null while it loads (or if it cannot). */
    public static Loaded get(WornCosmetics.Worn worn) {
        Loaded l = READY.get(worn.id);
        if (l != null) return l;
        request(worn);
        return null;
    }

    /** A plain white texture, for materials with a colour and no image. */
    public static ResourceLocation white() {
        if (white == null) {
            NativeImage img = new NativeImage(1, 1, false);
            Images.setPixelArgb(img, 0, 0, 0xFFFFFFFF);
            white = dev.breeze.compat.Ids.breeze("cosmetics/white");
            Images.register(white, img);
        }
        return white;
    }

    private static void request(WornCosmetics.Worn worn) {
        if (FAILED.contains(worn.id) || !LOADING.add(worn.id)) return;
        if (!CapeTextures.allowedUrl(worn.modelUrl)) {
            BreezeClient.LOGGER.warn("[Breeze] cosmetic {} refused: model is not served by the Breeze API over https", worn.id);
            fail(worn.id);
            return;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(worn.modelUrl)).timeout(Duration.ofSeconds(20)).GET().build();
            CapeTextures.http().sendAsync(req, HttpResponse.BodyHandlers.ofByteArray())
                    .whenComplete((res, err) -> {
                        if (err != null || res == null || res.statusCode() != 200 || res.body() == null) {
                            fail(worn.id);
                            return;
                        }
                        load(worn.id, worn.bounds, res.body());
                    });
        } catch (Throwable t) {
            fail(worn.id);
        }
    }

    /** Reads a GLB off the render thread and uploads its textures on it. Also used by the self-test. */
    public static void load(String id, float[] knownBounds, byte[] glb) {
        LOADING.add(id);
        CompletableFuture.supplyAsync(() -> {
            GlbModel model = GlbReader.read(glb);
            float[] bounds = knownBounds != null ? knownBounds : Pose.rest(model).bounds();
            return new Object[]{model, bounds};
        }).whenComplete((parsed, err) -> {
            if (err != null || parsed == null) {
                BreezeClient.LOGGER.warn("[Breeze] cosmetic {} could not be read: {}", id,
                        err == null ? "empty" : err.getCause() != null ? err.getCause().toString() : err.toString());
                fail(id);
                return;
            }
            Minecraft.getInstance().execute(() -> publish(id, (GlbModel) parsed[0], (float[]) parsed[1]));
        });
    }

    private static void publish(String id, GlbModel model, float[] bounds) {
        try {
            List<ResourceLocation> owned = new ArrayList<>();
            ResourceLocation[] images = new ResourceLocation[model.images.size()];
            String base = "cosmetics/" + id.toLowerCase().replaceAll("[^a-z0-9_.-]", "_") + "/";
            for (int i = 0; i < images.length; i++) {
                NativeImage img = model.images.get(i).bytes.length == 0 ? null : CapeTextures.decode(model.images.get(i).bytes);
                if (img == null) {
                    images[i] = white();
                    continue;
                }
                ResourceLocation rl = dev.breeze.compat.Ids.breeze(base + i);
                Images.register(rl, img);
                owned.add(rl);
                images[i] = rl;
            }
            TEXTURES.put(id, owned);
            READY.put(id, new Loaded(model, bounds, images));
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] cosmetic {} textures failed: {}", id, t.toString());
            fail(id);
        } finally {
            LOADING.remove(id);
        }
    }

    private static void fail(String id) {
        LOADING.remove(id);
        FAILED.add(id);
    }

    /** Frees every model's textures (disconnect). */
    public static void releaseAll() {
        Minecraft mc = Minecraft.getInstance();
        List<ResourceLocation> all = new ArrayList<>();
        for (List<ResourceLocation> l : TEXTURES.values()) all.addAll(l);
        TEXTURES.clear();
        READY.clear();
        FAILED.clear();
        mc.execute(() -> {
            for (ResourceLocation rl : all) {
                try { mc.getTextureManager().release(rl); } catch (Throwable ignored) { }
            }
        });
    }
}
