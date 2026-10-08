package dev.breeze.web;


import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.breeze.BreezeClient;
import dev.breeze.LastServer;
import dev.breeze.Module;
import dev.breeze.bridge.BridgeActions;
import dev.breeze.bridge.BridgeException;
import dev.breeze.bridge.ExternalLinks;
import dev.breeze.bridge.Request;
import dev.breeze.bridge.Router;
import dev.breeze.config.BreezeConfig;
import dev.breeze.cosmetics.AccountCapes;
import dev.breeze.cosmetics.CapePreviews;
import dev.breeze.cosmetics.CosmeticActions;
import dev.breeze.cosmetics.CosmeticState;
import dev.breeze.cosmetics.OwnedModels;
import dev.breeze.cosmetics.WornCosmetics;
import dev.breeze.menu.HudEditorScreen;
import dev.breeze.net.BreezeApi;
import dev.breeze.net.BreezePresence;
import dev.breeze.net.FriendsClient;
import dev.breeze.net.HostManager;
import dev.breeze.net.Self;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Every bridge action, bound to the real game.
 *
 * Each handler runs on the thread contract/bridge.json declares for it (see
 * {@link BridgeActions}); the router enforces that, so a handler here never has
 * to think about threads beyond not blocking. Anything that changes the screen
 * is deferred through {@link BreezeWebScreen#afterAnswer}, so the page gets its
 * answer before its browser is closed by the transition.
 */
final class Handlers {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Pattern ROUTE = Pattern.compile("[a-z]+(/[A-Za-z0-9 _.-]{1,40})?");

    private Handlers() {}

    static Router build(BreezeWebScreen screen) {
        Minecraft mc = Minecraft.getInstance();
        Router r = new Router(mc::execute);
        boolean ingame = screen.ingame();

        // ── app ──────────────────────────────────────────────────────────────
        on(r, "app.hello", p -> {
            JsonObject o = new JsonObject();
            o.addProperty("protocol", Request.PROTOCOL);
            o.addProperty("modVersion", version("breeze"));
            o.addProperty("minecraftVersion", net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getModContainer("minecraft").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?"));
            o.addProperty("loaderVersion", version("fabricloader"));
            o.addProperty("chromiumVersion", WebInit.chromiumVersion());
            o.addProperty("context", ingame ? "ingame" : "title");
            o.addProperty("lastRoute", UiState.lastRoute(ingame));
            JsonObject f = new JsonObject();
            f.addProperty("cosmetics", true);
            f.addProperty("friends", true);
            f.addProperty("hosting", true);
            f.addProperty("vanillaMenu", !ingame);
            o.add("features", f);
            return o;
        });
        on(r, "app.openExternal", p -> {
            var uri = ExternalLinks.check(p.str("url", 500));
            dev.breeze.compat.Links.open(uri);
            return Router.ok();
        });

        // ── ui ───────────────────────────────────────────────────────────────
        on(r, "ui.close", p -> {
            if (!ingame) throw BridgeException.forbidden("The title menu stays open. Use Quit to leave Minecraft.");
            screen.afterAnswer(() -> dev.breeze.compat.ActiveScreen.set(mc, null));
            return Router.ok();
        });
        on(r, "ui.escapeAck", p -> {
            screen.onEscapeAck(p.bool("handled"));
            return Router.ok();
        });
        on(r, "ui.route", p -> {
            String route = p.str("route", 60);
            if (!ROUTE.matcher(route).matches()) throw BridgeException.invalid("Unknown route.");
            UiState.rememberRoute(ingame, route);
            return Router.ok();
        });
        on(r, "ui.vanillaMenu", p -> {
            if (ingame) throw BridgeException.forbidden("Minecraft's title screen is only available from the title menu.");
            UiState.useVanillaTitle(true);
            screen.afterAnswer(() -> dev.breeze.compat.ActiveScreen.set(mc, new TitleScreen()));
            return Router.ok();
        });

        // ── game ─────────────────────────────────────────────────────────────
        on(r, "game.state", p -> gameState(mc));
        on(r, "game.singleplayer", p -> {
            if (ingame) throw BridgeException.forbidden("Leave this world first.");
            screen.afterAnswer(() -> dev.breeze.compat.ActiveScreen.set(mc, new SelectWorldScreen(screen)));
            return Router.ok();
        });
        on(r, "game.multiplayer", p -> {
            if (ingame) throw BridgeException.forbidden("Leave this world first.");
            screen.afterAnswer(() -> dev.breeze.compat.ActiveScreen.set(mc, new JoinMultiplayerScreen(screen)));
            return Router.ok();
        });
        on(r, "game.joinServer", p -> {
            if (ingame || mc.level != null) throw BridgeException.forbidden("Leave this world first.");
            String address = p.str("address", 255);
            ServerData last = LastServer.get();
            // Only the server the player really joined last: the page cannot
            // send the game anywhere the player has not chosen to go.
            if (last == null || !address.equals(last.ip)) throw BridgeException.forbidden("That is not a server you have joined.");
            screen.afterAnswer(() -> dev.breeze.compat.Net.connect(screen, mc, last.ip, last));
            return Router.ok();
        });
        on(r, "game.options", p -> {
            screen.afterAnswer(() -> dev.breeze.compat.ActiveScreen.set(mc, dev.breeze.menu.ReturnTo.from(screen, dev.breeze.compat.Screens.options(screen, mc))));
            return Router.ok();
        });
        on(r, "game.pauseMenu", p -> {
            if (!ingame || mc.level == null) throw BridgeException.forbidden("There is no game to pause.");
            screen.afterAnswer(() -> dev.breeze.compat.ActiveScreen.set(mc, new PauseScreen(true)));
            return Router.ok();
        });
        on(r, "game.quit", p -> {
            if (mc.level != null) throw BridgeException.forbidden("Leave the world from the game menu first, so it is saved.");
            screen.afterAnswer(mc::stop);
            return Router.ok();
        });

        // ── settings ─────────────────────────────────────────────────────────
        on(r, "settings.get", p -> UiState.snapshot());
        on(r, "settings.set", p -> {
            String key = p.str("key", 40);
            JsonElement value = p.value("value");
            UiState.set(key, value);
            return UiState.snapshot();
        });
        on(r, "settings.reset", p -> {
            UiState.reset();
            return UiState.snapshot();
        });

        // ── modules ──────────────────────────────────────────────────────────
        on(r, "modules.list", p -> ModuleJson.all());
        on(r, "modules.icons", p -> dev.breeze.ui.ModuleIconCache.json());
        on(r, "modules.setEnabled", p -> {
            Module m = ModuleJson.find(p.str("name", 64));
            m.setEnabled(p.bool("enabled"));
            BreezeConfig.save();
            return ModuleJson.of(m);
        });
        on(r, "modules.setSetting", p -> {
            Module m = ModuleJson.find(p.str("name", 64));
            ModuleJson.set(m, p.str("id", 64), p.value("value"));
            BreezeConfig.save();
            return ModuleJson.of(m);
        });
        on(r, "modules.reset", p -> {
            Module m = ModuleJson.find(p.str("name", 64));
            ModuleJson.reset(m);
            BreezeConfig.save();
            return ModuleJson.of(m);
        });
        on(r, "hud.openEditor", p -> {
            if (mc.level == null || mc.player == null) {
                throw BridgeException.unavailable("Open a world to edit the HUD, so you can see it while you move it.");
            }
            screen.afterAnswer(() -> dev.breeze.compat.ActiveScreen.set(mc, new HudEditorScreen(screen)));
            return Router.ok();
        });

        // ── installed mods and account ───────────────────────────────────────
        on(r, "mods.list", p -> modsList());
        on(r, "integrations.list", p -> dev.breeze.integrations.Integrations.json(mc, true));
        on(r, "integrations.open", p -> {
            String id = p.str("id", 96);
            String action = p.str("action", 160);
            net.minecraft.client.gui.screens.Screen next;
            try {
                next = dev.breeze.integrations.Integrations.screen(id, action, screen);
            } catch (dev.breeze.integrations.Integrations.Unavailable u) {
                throw BridgeException.unavailable(u.getMessage());
            }
            screen.afterAnswer(() -> dev.breeze.compat.ActiveScreen.set(mc, next));
            return Router.ok();
        });
        on(r, "account.get", p -> account(mc));

        // ── cosmetics (Breeze API) ───────────────────────────────────────────
        on(r, "cosmetics.state", p -> {
            CosmeticState.Entry e = selfForCapes(6_000);
            AccountCapes.invalidate();
            AccountCapes.await(e, 4_000);
            // The first open waits briefly for the cape pictures; after that
            // they come from memory.
            List<CosmeticState.CapeInfo> shown = new ArrayList<>(AccountCapes.owned(e));
            shown.addAll(AccountCapes.notOwned(e));
            CapePreviews.await(shown, 2_500);
            return cosmetics(e);
        });
        on(r, "cosmetics.equipCape", p -> {
            String id = p.optStr("id", 64);
            CosmeticState.Entry before = selfForCapes(6_000);
            AccountCapes.await(before, 2_000);
            if (id != null && !AccountCapes.owns(before, id)) throw BridgeException.forbidden("That cape is not on your account.");
            if (id == null && !AccountCapes.canRemove(before)) {
                throw BridgeException.unavailable("Take your cape off in the Breeze launcher's Wardrobe.");
            }
            CompletableFuture<Boolean> done = new CompletableFuture<>();
            CosmeticActions.equipCape(id, done::complete);
            boolean ok;
            // Worst case 6 s load + 2 s list + 8 s request + 4 s refresh stays
            // inside the 20 s IO timeout, so the page gets this answer.
            try {
                ok = done.get(8, TimeUnit.SECONDS);
            } catch (Exception e) {
                ok = false;
            }
            if (!ok) throw BridgeException.unavailable("The cape was not changed. Try again in a moment.");
            // The cape drawn on you follows at once, not at the next poll.
            AccountCapes.noteSelected(id);
            AccountCapes.invalidate();
            dev.breeze.cape.ServerCapes.applied(id);
            if (!AccountCapes.fromState(before)) return cosmetics(before);
            return cosmetics(awaitCape(id, 4_000));
        });

        on(r, "cosmetics.equipModel", p -> {
            String id = p.str("id", 64);
            if (OwnedModels.await(false, 3_000) != OwnedModels.Status.READY) {
                throw BridgeException.unavailable("3D cosmetics cannot be changed from the game right now. Use the Breeze launcher's Wardrobe.");
            }
            if (!OwnedModels.owns(id)) throw BridgeException.forbidden("That cosmetic is not on your account.");
            if (!changeModel(done -> OwnedModels.equip(id, done))) {
                throw BridgeException.unavailable("The cosmetic was not changed. Try again in a moment.");
            }
            // Worst case 3 + 7 + 3 + 3 s stays inside the 20 s IO timeout.
            return cosmetics(loadedSelf(3_000));
        });
        on(r, "cosmetics.unequipModel", p -> {
            String slot = p.str("slot", 16);
            if (!dev.breeze.cosmetics.OwnedCosmetics.validSlot(slot)) throw BridgeException.invalid("That is not a cosmetic slot.");
            if (OwnedModels.await(false, 3_000) != OwnedModels.Status.READY) {
                throw BridgeException.unavailable("3D cosmetics cannot be changed from the game right now. Use the Breeze launcher's Wardrobe.");
            }
            if (!changeModel(done -> OwnedModels.unequip(slot, done))) {
                throw BridgeException.unavailable("The cosmetic was not changed. Try again in a moment.");
            }
            // Worst case 3 + 7 + 3 + 3 s stays inside the 20 s IO timeout.
            return cosmetics(loadedSelf(3_000));
        });

        // ── friends (Breeze API, polled by FriendsClient) ────────────────────
        on(r, "friends.list", p -> friends());
        on(r, "friends.request", p -> {
            requireSignedIn();
            String name = p.str("name", 16);
            if (!NAME.matcher(name).matches()) throw BridgeException.invalid("That is not a Minecraft name.");
            FriendsClient.requestFriend(mc, name);
            return settleFriends(mc);
        });
        on(r, "friends.respond", p -> {
            requireSignedIn();
            UUID id = p.uuid("uuid");
            if (p.bool("accept")) FriendsClient.accept(mc, id);
            else FriendsClient.deny(mc, id);
            return settleFriends(mc);
        });
        on(r, "friends.remove", p -> {
            requireSignedIn();
            FriendsClient.remove(mc, p.uuid("uuid"));
            return settleFriends(mc);
        });

        // ── hosting ──────────────────────────────────────────────────────────
        on(r, "hosting.state", p -> hosting());
        on(r, "hosting.start", p -> {
            requireSignedIn();
            if (!HostManager.canHost()) throw BridgeException.unavailable("Open a singleplayer world first.");
            List<UUID> invite = new ArrayList<>();
            Set<UUID> friends = friendIds();
            for (String s : p.strList("invite", 50, 36)) {
                UUID u;
                try {
                    u = UUID.fromString(s);
                } catch (IllegalArgumentException e) {
                    throw BridgeException.invalid("An invite is not a player id.");
                }
                if (!friends.contains(u)) throw BridgeException.forbidden("You can only invite your friends.");
                invite.add(u);
            }
            HostManager.startHosting(invite);
            return hosting();
        });
        on(r, "hosting.stop", p -> {
            HostManager.stopHosting();
            return hosting();
        });
        on(r, "hosting.join", p -> {
            requireSignedIn();
            UUID host = p.uuid("host");
            HostManager.Invite invite = null;
            for (HostManager.Invite i : invites()) {
                if (i.host.equals(host)) invite = i;
            }
            if (invite == null) throw BridgeException.unavailable("That invite has expired.");
            HostManager.Invite chosen = invite;
            screen.afterAnswer(() -> HostManager.joinFriend(chosen));
            return hosting();
        });

        Set<String> missing = r.missing(BridgeActions.ALL);
        if (!missing.isEmpty()) BreezeClient.LOGGER.error("[Breeze] bridge actions with no handler: {}", missing);
        return r;
    }

    private static void on(Router r, String action, Router.Handler h) {
        Router.Thread t = BridgeActions.thread(action);
        if (t == null) throw new IllegalStateException("Not in the contract: " + action);
        r.register(action, t, t == Router.Thread.IO ? 20_000 : Router.defaultTimeout(t), h);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static String version(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
    }

    private static JsonObject gameState(Minecraft mc) {
        JsonObject o = new JsonObject();
        boolean inWorld = mc.level != null && mc.player != null;
        o.addProperty("inWorld", inWorld);
        o.addProperty("singleplayer", mc.getSingleplayerServer() != null);
        o.addProperty("worldName", mc.getSingleplayerServer() != null
                ? mc.getSingleplayerServer().getWorldData().getLevelName() : null);
        ServerData server = mc.getCurrentServer();
        o.addProperty("serverName", server != null && mc.getSingleplayerServer() == null ? server.name : null);
        o.addProperty("serverAddress", server != null && mc.getSingleplayerServer() == null ? server.ip : null);
        o.addProperty("playerName", Self.name(mc));
        o.addProperty("dimension", mc.level != null ? dev.breeze.compat.Ids.keyName(mc.level.dimension()) : null);
        o.addProperty("fps", dev.breeze.compat.Perf.fps(mc));
        Integer ping = null;
        if (inWorld && mc.getConnection() != null && mc.getSingleplayerServer() == null) {
            PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
            if (info != null) ping = info.getLatency();
        }
        o.addProperty("pingMs", ping);
        ServerData last = LastServer.get();
        if (last != null && last.ip != null && !last.ip.isBlank()) {
            JsonObject l = new JsonObject();
            l.addProperty("name", last.name == null || last.name.isBlank() ? last.ip : last.name);
            l.addProperty("address", last.ip);
            o.add("lastServer", l);
        } else {
            o.add("lastServer", null);
        }
        return o;
    }

    private static JsonArray modsList() {
        JsonArray out = new JsonArray();
        for (dev.breeze.integrations.LoadedMod m : dev.breeze.integrations.Integrations.mods()) {
            // Fabric API ships as dozens of nested modules; list the parent only.
            if (m.id().startsWith("fabric-") && !m.id().equals("fabric-api") && m.parent() != null) continue;
            JsonObject o = new JsonObject();
            o.addProperty("id", m.id());
            o.addProperty("name", m.name());
            o.addProperty("version", m.version());
            o.addProperty("description", m.description());
            JsonArray authors = new JsonArray();
            for (String a : m.authors()) authors.add(a);
            o.add("authors", authors);
            o.addProperty("builtin", m.builtin());
            o.addProperty("kind", m.kind().json);
            if (m.parent() != null) o.addProperty("parent", m.parent());
            out.add(o);
        }
        return out;
    }

    private static JsonObject account(Minecraft mc) {
        JsonObject o = new JsonObject();
        UUID self = Self.selfUuid();
        o.addProperty("playerName", mc.getUser().getName());
        o.addProperty("uuid", self == null ? "" : self.toString());
        String state = switch (BreezeApi.authState()) {
            case READY -> "ready";
            case SIGNING_IN -> "signing-in";
            case UNREACHABLE -> "unreachable";
            default -> "signed-out";
        };
        if (!BreezePresence.enabled()) state = "signed-out";
        o.addProperty("breeze", state);
        String role = null;
        if (self != null && "ready".equals(state)) {
            CosmeticState.Entry e = CosmeticState.get(self);
            if (e.loaded) role = normaliseRole(e.role);
        }
        o.addProperty("role", role);
        return o;
    }

    private static String normaliseRole(String role) {
        if (role == null) return "user";
        return switch (role.toLowerCase()) {
            case "owner" -> "owner";
            case "developer" -> "developer";
            case "creator" -> "creator";
            default -> "user";
        };
    }

    private static void requireSignedIn() {
        if (!BreezePresence.enabled() || Self.selfUuid() == null
                || BreezeApi.authState() == BreezeApi.AuthState.NOT_SIGNED_IN) {
            throw BridgeException.forbidden("Sign in to Breeze in the launcher to use this.");
        }
    }

    /**
     * The local player's cosmetic state for the capes, waiting (on the IO
     * thread) for the first load. Unlike {@link #loadedSelf} it does not give
     * up when the state never loads: the Wardrobe then lists the capes from
     * the older routes (AccountCapes).
     */
    private static CosmeticState.Entry selfForCapes(long waitMs) {
        requireSignedIn();
        UUID self = Self.selfUuid();
        long until = System.currentTimeMillis() + waitMs;
        CosmeticState.Entry e = CosmeticState.get(self);
        while (!e.loaded && System.currentTimeMillis() < until) {
            sleep(100);
            e = CosmeticState.get(self);
        }
        return e;
    }

    /** The local player's cosmetic state, waiting (on the IO thread) for the first load. */
    private static CosmeticState.Entry loadedSelf(long waitMs) {
        requireSignedIn();
        UUID self = Self.selfUuid();
        long until = System.currentTimeMillis() + waitMs;
        CosmeticState.Entry e = CosmeticState.get(self);
        while (!e.loaded && System.currentTimeMillis() < until) {
            sleep(100);
            e = CosmeticState.get(self);
        }
        if (!e.loaded) throw BridgeException.unavailable("Your capes could not be loaded. Check your connection and try again.");
        return e;
    }

    /** After an equip, wait for the next poll to report the new cape. */
    private static CosmeticState.Entry awaitCape(String id, long waitMs) {
        UUID self = Self.selfUuid();
        long until = System.currentTimeMillis() + waitMs;
        CosmeticState.Entry e = CosmeticState.get(self);
        while (System.currentTimeMillis() < until) {
            String now = e.cape == null ? null : e.cape.id;
            if (e.loaded && (id == null ? now == null : id.equals(now))) break;
            sleep(150);
            e = CosmeticState.get(self);
        }
        return e;
    }

    /**
     * Runs one equip or unequip and waits for the API's answer, then for the
     * refreshed owned list, so the page gets the state after the change.
     */
    private static boolean changeModel(java.util.function.Consumer<java.util.function.Consumer<Boolean>> change) {
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        change.accept(done::complete);
        boolean ok;
        try {
            ok = done.get(7, TimeUnit.SECONDS);
        } catch (Exception e) {
            ok = false;
        }
        OwnedModels.await(true, 3_000);
        return ok;
    }

    private static JsonObject cosmetics(CosmeticState.Entry e) {
        JsonObject o = new JsonObject();
        JsonArray capes = new JsonArray();
        String equipped = AccountCapes.equippedId(e);
        for (CosmeticState.CapeInfo c : AccountCapes.owned(e)) {
            JsonObject j = capeJson(c);
            j.addProperty("equipped", c.id.equalsIgnoreCase(equipped == null ? "" : equipped));
            // A personal cape is your own upload: it is only listed while you
            // wear it, and equipping another cape replaces it, as in the launcher.
            j.addProperty("personal", c.id.startsWith(AccountCapes.PERSONAL_ID));
            capes.add(j);
        }
        o.add("capes", capes);
        o.addProperty("equippedCapeId", equipped);
        o.addProperty("canRemove", AccountCapes.canRemove(e));
        // The rest of the catalogue: got in the launcher's store, then worn here.
        JsonArray store = new JsonArray();
        for (CosmeticState.CapeInfo c : AccountCapes.notOwned(e)) {
            JsonObject j = capeJson(c);
            j.addProperty("equipped", false);
            j.addProperty("personal", false);
            store.add(j);
        }
        o.add("store", store);
        // 3D cosmetics are listed only: changing them needs the account's
        // sign-in, which stays in the launcher.
        JsonArray worn = new JsonArray();
        for (WornCosmetics.Worn w : WornCosmetics.account(1_500)) {
            JsonObject j = new JsonObject();
            j.addProperty("id", w.id);
            j.addProperty("name", w.name == null || w.name.isBlank() ? w.id : w.name);
            j.addProperty("slot", w.slot == null ? "" : w.slot);
            worn.add(j);
        }
        o.add("worn", worn);
        // Everything the account owns, when the game can equip it; null
        // otherwise (an API without the in-game routes, or not signed in).
        if (OwnedModels.await(false, 1_500) == OwnedModels.Status.READY) {
            JsonArray owned = new JsonArray();
            for (dev.breeze.cosmetics.OwnedCosmetics.Item i : OwnedModels.items()) {
                JsonObject j = new JsonObject();
                j.addProperty("id", i.id);
                j.addProperty("name", i.name);
                j.addProperty("slot", i.slot);
                j.addProperty("equipped", i.equipped);
                owned.add(j);
            }
            o.add("owned", owned);
        } else {
            o.add("owned", com.google.gson.JsonNull.INSTANCE);
        }
        return o;
    }

    private static JsonObject capeJson(CosmeticState.CapeInfo c) {
        JsonObject j = new JsonObject();
        j.addProperty("id", c.id);
        j.addProperty("name", c.name == null || c.name.isBlank() ? "Cape" : c.name);
        // Fetched by the mod and handed over as data: the page itself never
        // loads remote images. Null until it has arrived.
        j.addProperty("preview", CapePreviews.get(c));
        return j;
    }

    private static JsonObject friends() {
        requireSignedIn();
        JsonObject o = new JsonObject();
        JsonArray friends = new JsonArray();
        for (FriendsClient.Entry e : FriendsClient.FRIENDS) {
            JsonObject j = new JsonObject();
            j.addProperty("uuid", e.id.toString());
            j.addProperty("name", e.name);
            j.addProperty("online", e.online);
            friends.add(j);
        }
        o.add("friends", friends);
        o.add("incoming", people(FriendsClient.INCOMING));
        o.add("outgoing", people(FriendsClient.OUTGOING));
        long at = FriendsClient.updatedAt;
        if (at > 0) o.addProperty("updatedAt", at);
        else o.add("updatedAt", null);
        o.addProperty("status", FriendsClient.status == null ? "" : FriendsClient.status);
        return o;
    }

    private static JsonArray people(List<FriendsClient.Entry> list) {
        JsonArray a = new JsonArray();
        for (FriendsClient.Entry e : list) {
            JsonObject j = new JsonObject();
            j.addProperty("uuid", e.id.toString());
            j.addProperty("name", e.name);
            a.add(j);
        }
        return a;
    }

    /** Let an async friends call land, then refresh the list and answer with it. */
    private static JsonObject settleFriends(Minecraft mc) {
        sleep(900);
        FriendsClient.refresh(mc);
        long before = FriendsClient.updatedAt;
        long until = System.currentTimeMillis() + 4_000;
        while (FriendsClient.updatedAt == before && System.currentTimeMillis() < until) sleep(100);
        return friends();
    }

    private static Set<UUID> friendIds() {
        Set<UUID> ids = new java.util.HashSet<>();
        for (FriendsClient.Entry e : FriendsClient.FRIENDS) ids.add(e.id);
        return ids;
    }

    private static List<HostManager.Invite> invites() {
        synchronized (HostManager.INVITES) {
            return new ArrayList<>(HostManager.INVITES);
        }
    }

    private static JsonObject hosting() {
        JsonObject o = new JsonObject();
        o.addProperty("hosting", HostManager.hosting);
        o.addProperty("busy", HostManager.busy);
        o.addProperty("canHost", HostManager.canHost() && BreezePresence.enabled() && Self.selfUuid() != null);
        o.addProperty("status", HostManager.status == null ? "" : HostManager.status);
        JsonArray list = new JsonArray();
        for (HostManager.Invite i : invites()) {
            JsonObject j = new JsonObject();
            j.addProperty("host", i.host.toString());
            j.addProperty("hostName", i.hostName);
            list.add(j);
        }
        o.add("invites", list);
        return o;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BridgeException(dev.breeze.bridge.BridgeError.CANCELLED, "Cancelled.");
        }
    }
}
