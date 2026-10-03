package dev.breeze.cosmetics;

import dev.breeze.net.BreezeApi;
import dev.breeze.net.BreezePresence;
import dev.breeze.net.Self;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The capes on your own account for the Wardrobe, and the catalogue the rest
 * are got from (in the launcher's store).
 *
 * The cosmetic state lists them once it describes your account. The live API
 * answered that route with "no account" for every player until its fix (read
 * in CI on 2026-10-03), so the Wardrobe listed nothing. Then the list comes
 * from the older routes, which read the same tables: /capes/:uuid names the
 * catalogue capes you own, /selected/:uuid the one you wear, and the public
 * catalogue (GET /capes) gives their names and images. All three answer
 * without signing in, before a world is open.
 */
public final class AccountCapes {

    /** The id of your personal cape (an upload) when it comes from /selected. */
    public static final String PERSONAL_ID = "personal";

    private static final long CATALOGUE_MS = 5 * 60_000;
    private static final long OWNED_MS = 15_000;

    private static volatile List<CapeLists.CatalogueCape> catalogue = List.of();
    private static volatile long catalogueAt;
    private static volatile boolean catalogueInFlight;

    private static volatile List<String> ownedIds = List.of();
    /** Your selection from /selected/:uuid: a catalogue id, "personal", or "" for none. */
    private static volatile String selected = "";
    private static volatile long ownedAt;
    private static volatile int ownedInFlight;

    private AccountCapes() {}

    /** The cosmetic state describes your account, so it is the source. */
    public static boolean fromState(CosmeticState.Entry e) {
        return CapePolicy.trustState(e.loaded, e.username);
    }

    /**
     * Your capes: your personal cape first while you wear it, then the
     * catalogue capes you own, in the order the API lists them.
     */
    public static List<CosmeticState.CapeInfo> owned(CosmeticState.Entry e) {
        List<CosmeticState.CapeInfo> out = new ArrayList<>();
        CosmeticState.CapeInfo personal = personal(e);
        if (personal != null) out.add(personal);
        if (fromState(e)) {
            synchronized (e.ownedCapes) {
                out.addAll(e.ownedCapes);
            }
            return out;
        }
        refresh(false);
        Map<String, CapeLists.CatalogueCape> byId = catalogueById();
        for (String id : ownedIds) {
            CapeLists.CatalogueCape c = byId.get(id);
            out.add(c != null ? info(c)
                    // Owned but not public: the image is still served by id.
                    : new CosmeticState.CapeInfo(id, "Cape", BreezeApi.uri("/capefile/" + id).toString(),
                            null, false, 12, List.of()));
        }
        return out;
    }

    /** The id of the cape you wear, or null. */
    public static String equippedId(CosmeticState.Entry e) {
        if (fromState(e)) return e.cape == null ? null : e.cape.id;
        refresh(false);
        String s = selected;
        switch (CapePolicy.legacySource(s)) {
            case CATALOGUE: return s.trim().toLowerCase();
            case PERSONAL: return PERSONAL_ID;
            default: return null;
        }
    }

    /** Catalogue capes you do not own. They are got in the launcher's store. */
    public static List<CosmeticState.CapeInfo> notOwned(CosmeticState.Entry e) {
        refresh(false);
        Set<String> have = new HashSet<>();
        for (CosmeticState.CapeInfo c : owned(e)) {
            if (c.id != null) have.add(c.id.toLowerCase());
        }
        List<CosmeticState.CapeInfo> out = new ArrayList<>();
        for (CapeLists.CatalogueCape c : catalogue) {
            if (!have.contains(c.id)) out.add(info(c));
        }
        return out;
    }

    public static boolean owns(CosmeticState.Entry e, String id) {
        if (id == null) return false;
        for (CosmeticState.CapeInfo c : owned(e)) {
            if (id.equalsIgnoreCase(c.id)) return true;
        }
        return false;
    }

    /**
     * Whether a cape can be taken off from the game. The older route the mod
     * uses for that (POST /select "none") could not clear the account's cape
     * link on the live API until its fix, so the cape came straight back.
     */
    public static boolean canRemove(CosmeticState.Entry e) {
        return fromState(e);
    }

    /** Ask again at once: after a change, or when the Wardrobe opens. */
    public static void invalidate() {
        ownedAt = 0;
    }

    /** A change made from the game, so the list shows it before the next answer. */
    public static void noteSelected(String id) {
        selected = id == null ? "" : id;
    }

    /** Starts what is missing and waits up to {@code waitMs} for it. Never on the game thread. */
    public static void await(CosmeticState.Entry e, long waitMs) {
        refresh(false);
        long until = System.currentTimeMillis() + waitMs;
        while (System.currentTimeMillis() < until) {
            boolean ready = catalogueAt > 0 && (fromState(e) || (ownedAt > 0 && ownedInFlight == 0));
            if (ready) return;
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static CosmeticState.CapeInfo personal(CosmeticState.Entry e) {
        if (fromState(e)) {
            CosmeticState.CapeInfo c = e.cape;
            // The fixed API names it "personal-<uuid>-<hash>"; older ones "personal".
            return c != null && c.id != null && c.id.startsWith(PERSONAL_ID) ? c : null;
        }
        if (CapePolicy.legacySource(selected) != CapePolicy.LegacySource.PERSONAL) return null;
        UUID me = Self.selfUuid();
        if (me == null) return null;
        return new CosmeticState.CapeInfo(PERSONAL_ID, "Personal cape", BreezeApi.uri("/cape/" + me).toString(),
                "personal", false, 12, List.of());
    }

    private static CosmeticState.CapeInfo info(CapeLists.CatalogueCape c) {
        return new CosmeticState.CapeInfo(c.id, c.name, c.imageUrl, c.rarity, c.animated, c.fps, c.frames);
    }

    private static Map<String, CapeLists.CatalogueCape> catalogueById() {
        Map<String, CapeLists.CatalogueCape> m = new HashMap<>();
        for (CapeLists.CatalogueCape c : catalogue) m.put(c.id, c);
        return m;
    }

    private static synchronized void refresh(boolean force) {
        if (!BreezePresence.enabled()) return;
        long now = System.currentTimeMillis();
        if (!catalogueInFlight && (force || catalogueAt == 0 || now - catalogueAt > CATALOGUE_MS)) {
            catalogueInFlight = true;
            get("/capes", body -> {
                List<CapeLists.CatalogueCape> parsed = CapeLists.catalogue(body);
                if (!parsed.isEmpty()) catalogue = parsed;
            }, () -> {
                catalogueAt = System.currentTimeMillis();
                catalogueInFlight = false;
            });
        }
        UUID me = Self.selfUuid();
        if (me == null || ownedInFlight > 0 || (!force && ownedAt != 0 && now - ownedAt < OWNED_MS)) return;
        ownedInFlight = 2;
        Runnable settle = () -> {
            if (--ownedInFlight <= 0) {
                ownedInFlight = 0;
                ownedAt = System.currentTimeMillis();
            }
        };
        get("/capes/" + me, body -> ownedIds = CapeLists.ownedIds(body), settle);
        getSelected(me, settle);
    }

    private static void getSelected(UUID me, Runnable settle) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(BreezeApi.uri("/selected/" + me))
                    .timeout(Duration.ofSeconds(6)).GET();
            BreezeApi.authed(b);
            BreezeApi.http().sendAsync(b.build(), HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
                try {
                    if (err == null && res != null) {
                        if (res.statusCode() == 200) selected = res.body().trim();
                        // 404 is "nothing equipped"; anything else keeps the last answer.
                        else if (res.statusCode() == 404) selected = "";
                    }
                } finally {
                    synchronized (AccountCapes.class) { settle.run(); }
                }
            });
        } catch (Throwable t) {
            synchronized (AccountCapes.class) { settle.run(); }
        }
    }

    private static void get(String path, java.util.function.Consumer<String> ok, Runnable always) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(BreezeApi.uri(path))
                    .timeout(Duration.ofSeconds(6)).GET();
            BreezeApi.authed(b);
            BreezeApi.http().sendAsync(b.build(), HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
                try {
                    if (err == null && res != null && res.statusCode() == 200) ok.accept(res.body());
                } catch (Throwable ignored) {
                    // An unreadable answer keeps what was there.
                } finally {
                    synchronized (AccountCapes.class) { always.run(); }
                }
            });
        } catch (Throwable t) {
            synchronized (AccountCapes.class) { always.run(); }
        }
    }
}
