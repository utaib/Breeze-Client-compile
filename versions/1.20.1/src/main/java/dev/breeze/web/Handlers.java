package dev.breeze.web;

import com.cinemamod.mcef.MCEF;
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
import dev.breeze.cosmetics.CapePreviews;
import dev.breeze.cosmetics.CosmeticActions;
import dev.breeze.cosmetics.CosmeticState;
import dev.breeze.menu.HudEditorScreen;
import dev.breeze.net.BreezeApi;
import dev.breeze.net.BreezePresence;
import dev.breeze.net.FriendsClient;
import dev.breeze.net.HostManager;
import dev.breeze.net.Self;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.Person;
import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.OptionsScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

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
            o.addProperty("minecraftVersion", SharedConstants.getCurrentVersion().getName());
            o.addProperty("loaderVersion", version("fabricloader"));
            String chrome = null;
            try {
                chrome = MCEF.getApp().getHandle().getVersion().getChromeVersion();
            } catch (Throwable ignored) {
            }
            o.addProperty("chromiumVersion", chrome);
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
            Util.getPlatform().openUri(uri);
            return Router.ok();
        });

        // ── ui ───────────────────────────────────────────────────────────────
        on(r, "ui.close", p -> {
            if (!ingame) throw BridgeException.forbidden("The title menu stays open. Use Quit to leave Minecraft.");
            screen.afterAnswer(() -> mc.setScreen(null));
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
            screen.afterAnswer(() -> mc.setScreen(new TitleScreen()));
            return Router.ok();
        });

        // ── game ─────────────────────────────────────────────────────────────
        on(r, "game.state", p -> gameState(mc));
        on(r, "game.singleplayer", p -> {
            if (ingame) throw BridgeException.forbidden("Leave this world first.");
            screen.afterAnswer(() -> mc.setScreen(new SelectWorldScreen(screen)));
            return Router.ok();
        });
        on(r, "game.multiplayer", p -> {
            if (ingame) throw BridgeException.forbidden("Leave this world first.");
            screen.afterAnswer(() -> mc.setScreen(new JoinMultiplayerScreen(screen)));
            return Router.ok();
        });
        on(r, "game.joinServer", p -> {
            if (ingame || mc.level != null) throw BridgeException.forbidden("Leave this world first.");
            String address = p.str("address", 255);
            ServerData last = LastServer.get();
            // Only the server the player really joined last: the page cannot
            // send the game anywhere the player has not chosen to go.
            if (last == null || !address.equals(last.ip)) throw BridgeException.forbidden("That is not a server you have joined.");
            screen.afterAnswer(() -> ConnectScreen.startConnecting(screen, mc, ServerAddress.parseString(last.ip), last, false));
            return Router.ok();
        });
        on(r, "game.options", p -> {
            screen.afterAnswer(() -> mc.setScreen(new OptionsScreen(screen, mc.options)));
            return Router.ok();
        });
        on(r, "game.pauseMenu", p -> {
            if (!ingame || mc.level == null) throw BridgeException.forbidden("There is no game to pause.");
            screen.afterAnswer(() -> mc.setScreen(new PauseScreen(true)));
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
            screen.afterAnswer(() -> mc.setScreen(new HudEditorScreen(screen)));
            return Router.ok();
        });

        // ── installed mods and account ───────────────────────────────────────
        on(r, "mods.list", p -> modsList());
        on(r, "account.get", p -> account(mc));

        // ── cosmetics (Breeze API) ───────────────────────────────────────────
        on(r, "cosmetics.state", p -> {
            CosmeticState.Entry e = loadedSelf(6_000);
            // The first open waits briefly for the cape pictures; after that
            // they come from memory.
            List<CosmeticState.CapeInfo> owned;
            synchronized (e.ownedCapes) {
                owned = new ArrayList<>(e.ownedCapes);
            }
            CapePreviews.await(owned, 2_500);
            return cosmetics(e);
        });
        on(r, "cosmetics.equipCape", p -> {
            String id = p.optStr("id", 64);
            CosmeticState.Entry before = loadedSelf(6_000);
            if (id != null && !owns(before, id)) throw BridgeException.forbidden("That cape is not on your account.");
            CompletableFuture<Boolean> done = new CompletableFuture<>();
            CosmeticActions.equipCape(id, done::complete);
            boolean ok;
            // Worst case 6 s load + 8 s request + 4 s refresh stays inside the
            // 20 s IO timeout, so the page gets this answer, not a TIMEOUT.
            try {
                ok = done.get(8, TimeUnit.SECONDS);
            } catch (Exception e) {
                ok = false;
            }
            if (!ok) throw BridgeException.unavailable("The cape was not changed. Try again in a moment.");
            return cosmetics(awaitCape(id, 4_000));
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
        o.addProperty("dimension", mc.level != null ? mc.level.dimension().location().toString() : null);
        o.addProperty("fps", mc.getFps());
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
        List<ModContainer> mods = new ArrayList<>(FabricLoader.getInstance().getAllMods());
        mods.sort((a, b) -> a.getMetadata().getName().compareToIgnoreCase(b.getMetadata().getName()));
        for (ModContainer c : mods) {
            ModMetadata m = c.getMetadata();
            // Fabric API ships as dozens of nested modules; list the parent only.
            if (m.getId().startsWith("fabric-") && !m.getId().equals("fabric-api") && c.getContainingMod().isPresent()) continue;
            JsonObject o = new JsonObject();
            o.addProperty("id", m.getId());
            o.addProperty("name", m.getName());
            o.addProperty("version", m.getVersion().getFriendlyString());
            o.addProperty("description", m.getDescription() == null ? "" : m.getDescription());
            JsonArray authors = new JsonArray();
            for (Person person : m.getAuthors()) authors.add(person.getName());
            o.add("authors", authors);
            o.addProperty("builtin", "builtin".equals(m.getType()));
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

    private static boolean owns(CosmeticState.Entry e, String id) {
        synchronized (e.ownedCapes) {
            for (CosmeticState.CapeInfo c : e.ownedCapes) {
                if (id.equals(c.id)) return true;
            }
        }
        return false;
    }

    private static JsonObject cosmetics(CosmeticState.Entry e) {
        JsonObject o = new JsonObject();
        JsonArray capes = new JsonArray();
        String equipped = e.cape == null ? null : e.cape.id;
        synchronized (e.ownedCapes) {
            for (CosmeticState.CapeInfo c : e.ownedCapes) {
                JsonObject j = new JsonObject();
                j.addProperty("id", c.id);
                j.addProperty("name", c.name == null || c.name.isBlank() ? c.id : c.name);
                // Fetched by the mod and handed over as data: the page itself
                // never loads remote images. Null until it has arrived.
                j.addProperty("preview", CapePreviews.get(c));
                j.addProperty("equipped", c.id.equals(equipped));
                capes.add(j);
            }
        }
        o.add("capes", capes);
        o.addProperty("equippedCapeId", equipped);
        return o;
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
