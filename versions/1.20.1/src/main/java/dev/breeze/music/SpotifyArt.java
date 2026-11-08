package dev.breeze.music;

import com.mojang.blaze3d.platform.NativeImage;
import dev.breeze.BreezeClient;
import dev.breeze.compat.Ids;
import dev.breeze.compat.Images;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Album covers for the Spotify HUD and the Music screen.
 *
 * Spotify hands out cover pictures as JPEG on its own image hosts, and only
 * those hosts are fetched (anything else in a track's data is ignored). A
 * picture is decoded off the game thread and uploaded on it. Four texture
 * slots are reused in turn, so a long session of songs never piles up
 * textures: the fifth cover replaces the first.
 */
public final class SpotifyArt {

    private static final Set<String> HOSTS = Set.of("i.scdn.co", "mosaic.scdn.co", "image-cdn-ak.spotifycdn.com",
            "image-cdn-fa.spotifycdn.com", "thisis-images.spotifycdn.com", "seed-mix-image.spotifycdn.com");
    private static final int SLOTS = 4;
    /** Largest side kept: covers are drawn at most 64 GUI pixels. */
    private static final int MAX_SIDE = 128;

    /** A cover on the GPU: its texture and size in pixels. */
    public record Art(ResourceLocation id, int width, int height) {}

    /** Url to its texture, once uploaded. */
    private static final Map<String, Art> READY = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> LOADING = new ConcurrentHashMap<>();
    private static final Map<String, Long> FAILED_AT = new ConcurrentHashMap<>();
    private static final String[] slotUrl = new String[SLOTS];
    private static int nextSlot;

    private SpotifyArt() {}

    /** The cover's texture, or null while it loads (or when it cannot). Call on the game thread. */
    public static Art get(String url) {
        if (url == null || url.isEmpty()) return null;
        Art art = READY.get(url);
        if (art != null) return art;
        Long failed = FAILED_AT.get(url);
        if (failed != null && System.currentTimeMillis() - failed < 60_000) return null;
        if (LOADING.putIfAbsent(url, Boolean.TRUE) == null) fetch(url);
        return null;
    }

    private static boolean allowed(String url) {
        try {
            URI u = URI.create(url);
            return "https".equals(u.getScheme()) && u.getHost() != null && HOSTS.contains(u.getHost().toLowerCase(java.util.Locale.ROOT));
        } catch (Throwable t) {
            return false;
        }
    }

    private static void fetch(String url) {
        if (!allowed(url)) {
            LOADING.remove(url);
            FAILED_AT.put(url, Long.MAX_VALUE / 2);
            return;
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build();
        dev.breeze.net.BreezeApi.http().sendAsync(req, HttpResponse.BodyHandlers.ofByteArray()).whenComplete((res, err) -> {
            BufferedImage bi = null;
            try {
                if (err == null && res.statusCode() == 200 && res.body().length < 2_000_000) {
                    bi = ImageIO.read(new ByteArrayInputStream(res.body()));
                }
            } catch (Throwable ignored) {
            }
            if (bi == null) {
                LOADING.remove(url);
                FAILED_AT.put(url, System.currentTimeMillis());
                return;
            }
            BufferedImage picture = shrink(bi);
            Minecraft.getInstance().execute(() -> upload(url, picture));
        });
    }

    private static BufferedImage shrink(BufferedImage bi) {
        int w = bi.getWidth(), h = bi.getHeight();
        if (w <= MAX_SIDE && h <= MAX_SIDE) return bi;
        double s = MAX_SIDE / (double) Math.max(w, h);
        int nw = Math.max(1, (int) Math.round(w * s)), nh = Math.max(1, (int) Math.round(h * s));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(bi, 0, 0, nw, nh, null);
        g.dispose();
        return out;
    }

    private static void upload(String url, BufferedImage bi) {
        try {
            NativeImage img = new NativeImage(bi.getWidth(), bi.getHeight(), true);
            for (int y = 0; y < bi.getHeight(); y++) {
                for (int x = 0; x < bi.getWidth(); x++) {
                    Images.setPixelArgb(img, x, y, bi.getRGB(x, y) | 0xFF000000);
                }
            }
            int slot = nextSlot;
            nextSlot = (nextSlot + 1) % SLOTS;
            String old = slotUrl[slot];
            if (old != null) READY.remove(old);
            slotUrl[slot] = url;
            ResourceLocation rl = Ids.breeze("spotify_art/" + slot);
            Images.register(rl, img);
            READY.put(url, new Art(rl, bi.getWidth(), bi.getHeight()));
        } catch (Throwable t) {
            FAILED_AT.put(url, System.currentTimeMillis());
            BreezeClient.LOGGER.warn("[Breeze] Spotify cover not shown: {}", t.toString());
        } finally {
            LOADING.remove(url);
        }
    }
}
