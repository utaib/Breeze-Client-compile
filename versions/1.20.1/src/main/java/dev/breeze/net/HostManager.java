package dev.breeze.net;


import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.breeze.BreezeClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.HttpUtil;
import net.minecraft.world.level.GameType;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class HostManager {

    public static final class Invite {
        public final UUID host;
        public final String hostName;
        public final String session;

        public Invite(UUID host, String hostName, String session) {
            this.host = host;
            this.hostName = hostName;
            this.session = session;
        }
    }

    private static final Gson GSON = new Gson();

    public static volatile String status = "";
    public static volatile boolean hosting;
    public static volatile boolean busy;
    public static volatile String session = "";
    private static volatile int localPort;
    private static Thread controlThread;
    // Held so Stop can actually tear the tunnel down. Only the flag used to be
    // cleared, which left the relay session registered and joinable.
    private static volatile Socket controlSocket;
    private static final Set<Socket> PIPES = ConcurrentHashMap.newKeySet();

    public static final List<Invite> INVITES = new ArrayList<>();
    private static final Set<UUID> SEEN_INVITES = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> INVITED = ConcurrentHashMap.newKeySet();
    private static long lastInvitePoll;
    private static long lastHeartbeat;

    private HostManager() {}

    private static HttpClient http() {
        return BreezeApi.http();
    }

    public static boolean canHost() {
        return Minecraft.getInstance().getSingleplayerServer() != null;
    }

    public static void startHosting(List<UUID> invited) {
        if (busy) return;
        busy = true;
        status = "Opening world...";
        INVITED.addAll(invited);
        BreezeClient.LOGGER.info("[Breeze] host: starting, inviting {}", invited.size());
        new Thread(() -> {
            try {
                Minecraft mc = Minecraft.getInstance();
                IntegratedServer srv = mc.getSingleplayerServer();
                if (srv == null) {
                    status = "Only works while in a singleplayer world.";
                    return;
                }
                int port;
                if (srv.isPublished()) {
                    port = srv.getPort();
                } else {
                    port = HttpUtil.getAvailablePort();
                    int chosen = port;
                    // Cheats off. Passing true granted operator-level commands
                    // to every friend who joined through the relay. The world's
                    // own game mode is used rather than forcing survival on a
                    // creative or adventure world.
                    GameType mode = srv.getDefaultGameType();
                    mc.execute(() -> srv.publishServer(mode, false, chosen));
                    for (int i = 0; i < 20 && srv.getPort() <= 0; i++) Thread.sleep(250);
                    port = srv.getPort();
                }
                if (port <= 0) {
                    status = "Could not open the world to LAN.";
                    BreezeClient.LOGGER.warn("[Breeze] host: LAN publish failed (port {})", port);
                    return;
                }
                localPort = port;
                BreezeClient.LOGGER.info("[Breeze] host: world published on port {}", port);
                // Fresh for every run. The id used to be generated once per
                // game process, so an id that leaked while hosting one world
                // still opened the next one.
                session = UUID.randomUUID().toString().replace("-", "");

                status = "Connecting to Breeze relay...";
                if (!openControl()) {
                    status = "Could not reach the Breeze relay server.";
                    return;
                }
                hosting = true;
                lastHeartbeat = System.currentTimeMillis();
                status = "Hosting through Breeze - invite friends!";
                BreezeClient.LOGGER.info("[Breeze] host: relay control open, session {}", session);
                announce(new ArrayList<>(INVITED));
            } catch (Throwable t) {
                status = "Host failed: " + t.getMessage();
                BreezeClient.LOGGER.warn("[Breeze] host failed: {}", t.toString());
            } finally {
                busy = false;
            }
        }, "Breeze-Host").start();
    }

    private static boolean openControl() {
        try {
            Socket control = Relay.open("HOST " + session);
            controlSocket = control;
            controlThread = new Thread(() -> controlLoop(control), "Breeze-Host-Control");
            controlThread.setDaemon(true);
            controlThread.start();
            return true;
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] relay control failed: {}", t.toString());
            return false;
        }
    }

    private static void controlLoop(Socket control) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(control.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("OPEN ")) {
                    String cid = line.substring(5).trim();
                    openPipe(cid);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try { control.close(); } catch (Throwable ignored) {}
            // Only for the run this socket belongs to. After Stop and a quick
            // restart, the old loop ending must not report the new run as lost.
            if (hosting && controlSocket == control) status = "Relay disconnected.";
        }
    }

    private static void openPipe(String cid) {
        String sess = session;
        new Thread(() -> {
            Socket pipe = null;
            try {
                pipe = Relay.open("PIPE " + sess + " " + cid);
                // Pipes a friend already disconnected are closed by the pump;
                // drop them here so a long session does not accumulate them.
                PIPES.removeIf(Socket::isClosed);
                PIPES.add(pipe);
                // Stop may have run while the pipe was being opened.
                if (!hosting || !sess.equals(session)) {
                    closeQuietly(pipe);
                    return;
                }
                Socket world = new Socket("127.0.0.1", localPort);
                world.setTcpNoDelay(true);
                Relay.pump(pipe, world);
            } catch (Throwable e) {
                if (pipe != null) closeQuietly(pipe);
                BreezeClient.LOGGER.warn("[Breeze] relay pipe failed: {}", e.toString());
            }
        }, "Breeze-Host-Pipe").start();
    }

    private static void closeQuietly(Socket s) {
        PIPES.remove(s);
        try { s.close(); } catch (Throwable ignored) {}
    }

    public static void stopHosting() {
        hosting = false;
        INVITED.clear();
        status = "Stopped hosting.";
        BreezeClient.LOGGER.info("[Breeze] host: stopped");

        // Tear the tunnel down. Closing the control socket is what makes the
        // relay forget the session, so nobody else can join through it; the
        // pipes are the friends already connected through it. Closing a TLS
        // socket writes a close_notify, which can stall on a congested pipe,
        // so this runs off the calling thread, which is often the client tick.
        Socket control = controlSocket;
        Thread thread = controlThread;
        List<Socket> pipes = new ArrayList<>(PIPES);
        controlSocket = null;
        controlThread = null;
        PIPES.clear();
        session = "";
        Thread closer = new Thread(() -> {
            if (control != null) {
                try { control.close(); } catch (Throwable ignored) {}
            }
            if (thread != null) thread.interrupt();
            for (Socket p : pipes) {
                try { p.close(); } catch (Throwable ignored) {}
            }
        }, "Breeze-Host-Stop");
        closer.setDaemon(true);
        closer.start();

        UUID me = Self.selfUuid();
        if (!BreezePresence.enabled() || me == null) return;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(BreezeApi.uri("/host/stop/" + me))
                    .timeout(Duration.ofSeconds(4)).POST(HttpRequest.BodyPublishers.noBody());
            if (!BreezeApi.authed(b)) return;
            http().sendAsync(b.build(), HttpResponse.BodyHandlers.discarding())
                    .thenAccept(res -> BreezeApi.onUnauthorized(res.statusCode()));
        } catch (Throwable ignored) {}
    }

    private static void announce(List<UUID> invited) {
        Minecraft mc = Minecraft.getInstance();
        UUID me = Self.selfUuid();
        if (!BreezePresence.enabled() || me == null) return;
        if (BreezeApi.gameToken() == null) {
            // Without this the first announce of a hosting run is simply lost
            // and friends see no invite until the next heartbeat, 45 seconds
            // later. A zero heartbeat makes the tick retry once a token exists.
            lastHeartbeat = 0;
            return;
        }
        StringBuilder ids = new StringBuilder();
        for (UUID id : invited) {
            if (ids.length() > 0) ids.append(",");
            ids.append(id);
        }
        String body = "{\"session\":\"" + session + "\",\"name\":\"" + Self.name(mc) + "\",\"invited\":\"" + ids + "\"}";
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(BreezeApi.uri("/host/announce/" + me))
                    .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofString(body));
            if (!BreezeApi.authed(b)) return;
            http().sendAsync(b.build(), HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
                if (err != null || res == null) {
                    BreezeClient.LOGGER.warn("[Breeze] host announce failed: {}", err != null ? err.toString() : "no response");
                    return;
                }
                if (res.statusCode() != 200) {
                    BreezeApi.onUnauthorized(res.statusCode());
                    BreezeClient.LOGGER.warn("[Breeze] host announce failed: HTTP {}", res.statusCode());
                    if (hosting) status = res.statusCode() == 401 || res.statusCode() == 403
                            ? "Breeze did not accept this session; friends cannot see the invite yet."
                            : "Could not publish the invite to Breeze.";
                }
            });
        } catch (Throwable ignored) {}
    }

    public static void invite(UUID friend) {
        if (!hosting || !BreezePresence.enabled() || Self.selfUuid() == null) return;
        INVITED.add(friend);
        List<UUID> one = new ArrayList<>();
        one.add(friend);
        announce(one);
        status = "Invite sent.";
    }

    public static void joinFriend(Invite inv) {
        status = "Joining " + inv.hostName + "'s world...";
        BreezeClient.LOGGER.info("[Breeze] join: session {} from {}", inv.session, inv.hostName);
        int p = RelayClient.startJoin(inv.session);
        if (p <= 0) {
            status = "Could not open local join proxy.";
            return;
        }
        Connector.connect("127.0.0.1:" + p);
    }

    public static void tick(Minecraft mc) {
        UUID me = Self.selfUuid();
        if (!BreezePresence.enabled() || me == null) return;
        long now = System.currentTimeMillis();
        if (hosting) {
            if (mc.getSingleplayerServer() == null) {
                stopHosting();
            } else if (now - lastHeartbeat > 45000L) {
                lastHeartbeat = now;
                announce(new ArrayList<>(INVITED));
            }
        }
        if (now - lastInvitePoll < 8000L) return;
        // No token yet: skip without touching the timer, so the poll runs on
        // the first tick that has one.
        if (BreezeApi.gameToken() == null) return;
        lastInvitePoll = now;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(BreezeApi.uri("/host/invites/" + me))
                    .timeout(Duration.ofSeconds(5)).GET();
            if (!BreezeApi.authed(b)) return;
            http().sendAsync(b.build(), HttpResponse.BodyHandlers.ofString()).thenAccept(res -> {
                try {
                    if (res.statusCode() != 200) {
                        BreezeApi.onUnauthorized(res.statusCode());
                        return;
                    }
                    JsonArray arr = GSON.fromJson(res.body(), JsonArray.class);
                    if (arr == null) return;
                    List<Invite> fresh = new ArrayList<>();
                    for (JsonElement e : arr) {
                        JsonObject o = e.getAsJsonObject();
                        UUID host = UUID.fromString(o.get("host").getAsString());
                        String name = o.has("name") ? o.get("name").getAsString() : "?";
                        String sess = o.get("session").getAsString();
                        fresh.add(new Invite(host, name, sess));
                        if (SEEN_INVITES.add(host)) {
                            chat(mc, name + " invited you to their world! Press Esc > Friends to join.");
                        }
                    }
                    synchronized (INVITES) {
                        INVITES.clear();
                        INVITES.addAll(fresh);
                    }
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    private static void chat(Minecraft mc, String s) {
        try {
            dev.breeze.compat.Game.message(mc, Component.literal("§b[Breeze]§r " + s));
        } catch (Throwable ignored) {}
    }
}
