package dev.breeze.cosmetics;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cape images for the Wardrobe's cards, as data: URIs.
 *
 * The in-game renderer only downloads the cape you are wearing, and it uploads
 * it to the GPU. The Wardrobe needs every cape you own and only as a picture,
 * so this downloads the image (the first frame of an animated cape) with the
 * same rules as {@link CapeTextures}: only from the Breeze API over https, and
 * with a client that never follows redirects. Nothing is uploaded to the GPU.
 *
 * Kept for the session by cape id; a failed download is retried after a
 * minute.
 */
public final class CapePreviews {

    private static final long RETRY_MS = 60_000;

    private static final Map<String, String> READY = new ConcurrentHashMap<>();
    private static final Map<String, Long> FAILED_AT = new ConcurrentHashMap<>();
    private static final Set<String> LOADING = ConcurrentHashMap.newKeySet();

    private CapePreviews() {}

    /** The preview if it has arrived, otherwise null; asking starts the download. */
    public static String get(CosmeticState.CapeInfo cape) {
        if (cape == null || cape.id == null) return null;
        String ready = READY.get(cape.id);
        if (ready != null) return ready;
        request(cape);
        return null;
    }

    /** Downloaded or given up on, so waiting longer changes nothing. */
    public static boolean settled(CosmeticState.CapeInfo cape) {
        if (cape == null || cape.id == null) return true;
        return READY.containsKey(cape.id) || FAILED_AT.containsKey(cape.id);
    }

    /** Start every download, then wait up to {@code waitMs} for them. Never call on the game thread. */
    public static void await(List<CosmeticState.CapeInfo> capes, long waitMs) {
        for (CosmeticState.CapeInfo c : capes) get(c);
        long until = System.currentTimeMillis() + waitMs;
        while (System.currentTimeMillis() < until) {
            boolean all = true;
            for (CosmeticState.CapeInfo c : capes) {
                if (!settled(c)) { all = false; break; }
            }
            if (all) return;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void request(CosmeticState.CapeInfo cape) {
        Long failed = FAILED_AT.get(cape.id);
        if (failed != null) {
            if (System.currentTimeMillis() - failed < RETRY_MS) return;
            FAILED_AT.remove(cape.id);
        }
        if (!LOADING.add(cape.id)) return;

        String url = cape.animated && cape.frames != null && !cape.frames.isEmpty() ? cape.frames.get(0) : cape.imageUrl;
        if (!CapeTextures.allowedUrl(url)) {
            fail(cape.id);
            return;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url.trim())).timeout(Duration.ofSeconds(8)).GET().build();
            CapeTextures.http().sendAsync(req, HttpResponse.BodyHandlers.ofByteArray()).whenComplete((res, err) -> {
                String uri = err == null && res != null && res.statusCode() == 200 ? ImageData.dataUri(res.body()) : null;
                if (uri != null) {
                    READY.put(cape.id, uri);
                    LOADING.remove(cape.id);
                } else {
                    fail(cape.id);
                }
            });
        } catch (Throwable t) {
            fail(cape.id);
        }
    }

    private static void fail(String id) {
        FAILED_AT.put(id, System.currentTimeMillis());
        LOADING.remove(id);
    }
}
