package dev.breeze.cosmetics;

import com.mojang.blaze3d.platform.NativeImage;
import dev.breeze.compat.Ids;
import dev.breeze.net.BreezeApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cape textures, including animated ones.
 *
 * The mod previously registered exactly one static texture per cape, so an
 * animated cape rendered as whichever single frame happened to be its cover
 * image. Frames were never requested because the mod's protocol never carried
 * them; see {@link CosmeticState}.
 *
 * Animation here is a texture swap, not a shader: each frame is uploaded once
 * as its own {@link DynamicTexture} and the renderer is handed whichever one
 * the clock says is current. That is cheap per frame (a texture bind, which
 * happens anyway) at the cost of holding N textures resident.
 *
 * The lifecycle matters more than the animation. A DynamicTexture holds a GPU
 * allocation that is only reclaimed by an explicit release; dropping the Java
 * reference leaks it. Every registration here is tracked against its cape id so
 * {@link #release(String)} can free the whole set when the cape changes, which
 * is what stops a player who tries ten capes from leaking ten cape's worth of
 * VRAM. That matters especially on the integrated GPUs this client targets,
 * where VRAM is taken from system memory.
 */
public final class CapeTextures {

    /** Everything registered for one cape id, so it can all be freed together. */
    private static final Map<String, List<ResourceLocation>> REGISTERED = new ConcurrentHashMap<>();
    /** Decoded frame textures per cape, in play order. */
    private static final Map<String, List<ResourceLocation>> FRAMES = new ConcurrentHashMap<>();
    /** Capes whose download is in flight, so it is not started twice. */
    private static final Set<String> LOADING = ConcurrentHashMap.newKeySet();
    /** Capes whose download failed; not retried every frame. */
    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();

    private static HttpClient http;

    private CapeTextures() {}

    /**
     * The texture to draw for this cape right now.
     *
     * Returns null while the download is still in flight, which the caller
     * should treat as "no cape yet" rather than "no cape": the vanilla cape or
     * nothing is drawn for a frame or two and then it appears.
     */
    public static ResourceLocation current(CosmeticState.CapeInfo cape) {
        if (cape == null || cape.id == null) return null;

        List<ResourceLocation> frames = FRAMES.get(cape.id);
        if (frames == null || frames.isEmpty()) {
            request(cape);
            return null;
        }
        if (frames.size() == 1) return frames.get(0);

        // Wall-clock driven so every player's cape animates at its own declared
        // rate regardless of frame rate, and two players wearing the same cape
        // stay in step with each other.
        long periodMs = Math.max(1L, 1000L / Math.max(1, cape.fps));
        int index = (int) ((System.currentTimeMillis() / periodMs) % frames.size());
        return frames.get(index);
    }

    /** True once at least one frame is on the GPU and ready to draw. */
    public static boolean ready(CosmeticState.CapeInfo cape) {
        if (cape == null || cape.id == null) return false;
        List<ResourceLocation> f = FRAMES.get(cape.id);
        return f != null && !f.isEmpty();
    }

    /**
     * True when this cape's image could not be downloaded or decoded, as
     * opposed to still loading. Cleared when the cape is released, so equipping
     * it again tries again.
     */
    public static boolean failed(CosmeticState.CapeInfo cape) {
        return cape != null && cape.id != null && FAILED.contains(cape.id);
    }

    private static void request(CosmeticState.CapeInfo cape) {
        if (FAILED.contains(cape.id) || !LOADING.add(cape.id)) return;

        // An animated cape downloads its frames; a still one downloads its
        // single image. Both end up as a list, so the render path has no
        // special case.
        List<String> urls = cape.animated && cape.frames.size() > 1
                ? cape.frames
                : (cape.imageUrl == null ? List.of() : List.of(cape.imageUrl));

        if (urls.isEmpty()) {
            LOADING.remove(cape.id);
            FAILED.add(cape.id);
            return;
        }

        // These URLs come from the API response, and the game fetches them
        // from the player's own machine. Without a check, whoever could put a
        // URL on a cape could make every player who sees it request an
        // arbitrary address, including ones on their local network. Only the
        // API's own host, over https, is fetched.
        for (String url : urls) {
            if (!allowedUrl(url)) {
                dev.breeze.BreezeClient.LOGGER.warn("[Breeze] cape {} refused: image is not served by the Breeze API over https", cape.id);
                LOADING.remove(cape.id);
                FAILED.add(cape.id);
                return;
            }
        }

        List<byte[]> downloaded = new ArrayList<>();
        for (int i = 0; i < urls.size(); i++) downloaded.add(null);

        final int total = urls.size();
        final int[] remaining = { total };

        for (int i = 0; i < total; i++) {
            final int index = i;
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(urls.get(i)))
                        .timeout(Duration.ofSeconds(10)).GET().build();
                http().sendAsync(req, HttpResponse.BodyHandlers.ofByteArray())
                        .whenComplete((res, err) -> {
                            if (err == null && res != null && res.statusCode() == 200) {
                                downloaded.set(index, res.body());
                            }
                            synchronized (remaining) {
                                remaining[0]--;
                                if (remaining[0] > 0) return;
                            }
                            // All requests have settled. Texture registration
                            // must happen on the render thread: uploading to the
                            // GPU from an HTTP callback thread has no valid GL
                            // context and crashes.
                            Minecraft.getInstance().execute(() -> publish(cape, downloaded));
                        });
            } catch (Throwable t) {
                synchronized (remaining) { remaining[0]--; }
            }
        }
    }

    private static void publish(CosmeticState.CapeInfo cape, List<byte[]> data) {
        try {
            List<ResourceLocation> out = new ArrayList<>();
            List<ResourceLocation> owned = new ArrayList<>();

            for (int i = 0; i < data.size(); i++) {
                byte[] bytes = data.get(i);
                if (bytes == null || bytes.length == 0) continue;
                NativeImage img = decode(bytes);
                if (img == null) continue;

                ResourceLocation rl = Ids.breeze(
                        "cosmetic_capes/" + sanitize(cape.id) + "_" + i);
                Minecraft.getInstance().getTextureManager().register(rl, new DynamicTexture(img));
                out.add(rl);
                owned.add(rl);
            }

            if (out.isEmpty()) {
                FAILED.add(cape.id);
            } else {
                // Free anything previously registered for this id before
                // replacing it, or a re-fetch silently doubles the GPU cost.
                release(cape.id);
                FRAMES.put(cape.id, out);
                REGISTERED.put(cape.id, owned);
            }
        } catch (Throwable t) {
            FAILED.add(cape.id);
        } finally {
            LOADING.remove(cape.id);
        }
    }

    /**
     * Free every texture registered for a cape.
     *
     * Must be called when a cape stops being used. The texture manager holds
     * the GPU handle until it is explicitly released; letting the Java object
     * go out of scope does not reclaim video memory.
     */
    public static void release(String capeId) {
        if (capeId == null) return;
        List<ResourceLocation> owned = REGISTERED.remove(capeId);
        FRAMES.remove(capeId);
        FAILED.remove(capeId);
        if (owned == null || owned.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        // Release on the render thread for the same reason registration is.
        mc.execute(() -> {
            for (ResourceLocation rl : owned) {
                try { mc.getTextureManager().release(rl); } catch (Throwable ignored) { }
            }
        });
    }

    /** Free everything. Used on disconnect so a session does not accumulate capes. */
    public static void releaseAll() {
        for (String id : new ArrayList<>(REGISTERED.keySet())) release(id);
    }

    private static NativeImage decode(byte[] bytes) {
        try {
            return NativeImage.read(new ByteArrayInputStream(bytes));
        } catch (Throwable notPng) {
            // Some capes arrive as formats NativeImage cannot read directly.
            // ImageIO handles them, but its ARGB order has to be swapped to the
            // ABGR NativeImage expects, or every cape comes out with red and
            // blue exchanged.
            try {
                BufferedImage bi = ImageIO.read(new ByteArrayInputStream(bytes));
                if (bi == null) return null;
                NativeImage img = new NativeImage(bi.getWidth(), bi.getHeight(), true);
                for (int y = 0; y < bi.getHeight(); y++) {
                    for (int x = 0; x < bi.getWidth(); x++) {
                        int argb = bi.getRGB(x, y);
                        int abgr = (argb & 0xFF00FF00) | ((argb & 0xFF) << 16) | ((argb >> 16) & 0xFF);
                        img.setPixelRGBA(x, y, abgr);
                    }
                }
                return img;
            } catch (Throwable t) {
                return null;
            }
        }
    }

    /** ResourceLocation paths accept only [a-z0-9_.-/]. */
    private static String sanitize(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9_.-]", "_");
    }

    /**
     * True when a cape image URL points at the Breeze API itself: https, the
     * same host and the same port as {@link BreezeApi#baseUrl()}, and no user
     * info. Plain http is accepted only when the API base is itself http,
     * which only happens in development.
     */
    static boolean allowedUrl(String url) {
        if (url == null) return false;
        try {
            URI u = URI.create(url.trim());
            URI api = BreezeApi.baseUri();
            String scheme = u.getScheme();
            boolean schemeOk = "https".equalsIgnoreCase(scheme)
                    || ("http".equalsIgnoreCase(scheme) && "http".equalsIgnoreCase(api.getScheme()));
            return schemeOk
                    && u.getRawUserInfo() == null
                    && u.getHost() != null
                    && u.getHost().equalsIgnoreCase(api.getHost())
                    && effectivePort(u) == effectivePort(api);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static int effectivePort(URI u) {
        if (u.getPort() > 0) return u.getPort();
        return "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
    }

    // Deliberately not the shared BreezeApi client. That one follows
    // redirects, which would let an allowed URL bounce the fetch on to a host
    // allowedUrl never saw. This client keeps the default of never following.
    // Package-private for CapePreviews, which fetches under the same rules.
    static HttpClient http() {
        if (http == null) {
            http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        }
        return http;
    }
}
