package dev.breeze.recorder;

import dev.breeze.BreezeClient;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Breeze's recorder (the same on every version; what differs between
 * versions is in RecNet, RecGame, RecPlayer and Snapshot). While the player is connected it
 * keeps the few packets a recording must start with (the login, the
 * configuration the server sent, the world it joined); those are small and
 * are kept whether or not anything records. Recording adds, at its start, a
 * picture of the world as the client has it now (Snapshot), then every
 * packet the client receives and, each tick, the player's own state the
 * server never sends back (RecSelf). Packets are copied as bytes on the
 * network thread and written by RecWriter's own thread, so the render
 * thread only adds the player's state once a tick.
 */
public final class RecorderHooks {

    private record Rec(int kind, byte[] data) {}

    private static final Object LOCK = new Object();

    // The prelude of the current connection (guarded by LOCK).
    private static byte[] loginFinished;
    private static final List<byte[]> config = new ArrayList<>();
    private static boolean configDone;
    private static byte[] gameLogin;
    private static byte[] lastRespawn;

    private static volatile RecWriter writer;
    private static volatile boolean snapshotDone;
    private static final List<Rec> early = new ArrayList<>();
    private static long startNanos;
    private static Path file;
    private static final RecSelf self = new RecSelf();

    private RecorderHooks() {}

    public static boolean supported() {
        return true;
    }

    public static boolean recording() {
        return writer != null;
    }

    public static long recordedMs() {
        return writer == null ? -1 : (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** The recording being written (self-test), or null. */
    public static Path file() {
        return file;
    }

    // ── Packets (network thread) ───────────────────────────────────────────

    public static void onPacket(Connection connection, Packet<?> packet) {
        try {
            if (!RecNet.clientbound(connection) || Replay.isReplay(connection)) return;
            PacketListener l = connection.getPacketListener();
            int phase = RecNet.phase(l);
            if (phase < 0) return;
            if (phase == RecNet.LOGIN) {
                if (RecNet.isLoginFinished(packet)) {
                    byte[] b = RecNet.encode(phase, l, packet);
                    synchronized (LOCK) {
                        loginFinished = b;
                        config.clear();
                        configDone = false;
                        gameLogin = null;
                        lastRespawn = null;
                    }
                }
                return;
            }
            if (phase == RecNet.CONFIG) {
                byte[] b = RecNet.encode(phase, l, packet);
                synchronized (LOCK) {
                    // The server sent the configuration again (a reconfiguration): it replaces the first.
                    if (configDone) {
                        config.clear();
                        configDone = false;
                    }
                    config.add(b);
                }
                return;
            }
            boolean gameLoginPacket = RecNet.isGameLogin(packet);
            boolean remember = gameLoginPacket || RecNet.isRespawn(packet);
            if (remember) {
                byte[] b = RecNet.encode(phase, l, packet);
                synchronized (LOCK) {
                    configDone = true;
                    if (gameLoginPacket) {
                        gameLogin = b;
                        lastRespawn = null;
                    } else {
                        lastRespawn = b;
                    }
                }
            }
            if (writer == null) return;
            if (RecNet.isDisconnect(packet)) return;
            long t0 = System.nanoTime();
            Iterable<? extends Packet<?>> parts = RecNet.bundled(packet);
            if (parts != null) {
                for (Packet<?> p : parts) add(RecFormat.Kind.PACKET, RecNet.encode(phase, l, p));
            } else {
                add(RecFormat.Kind.PACKET, RecNet.encode(phase, l, packet));
            }
            copied.incrementAndGet();
            copyNanos.addAndGet(System.nanoTime() - t0);
        } catch (Throwable t) {
            // One packet Breeze cannot copy must never break the connection.
            BreezeClient.LOGGER.debug("[Breeze] recorder skipped a packet: {}", t.toString());
        }
    }

    private static void add(int kind, byte[] data) {
        RecWriter w = writer;
        if (w == null) return;
        synchronized (LOCK) {
            if (!snapshotDone) {
                early.add(new Rec(kind, data));
                return;
            }
        }
        copyBytes.addAndGet(data.length);
        w.record(kind, recordedMs(), data);
    }

    // ── Start and stop (render thread) ─────────────────────────────────────

    public static String start(Minecraft mc) {
        if (writer != null) return null;
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return "Join a world first, then start recording.";
        if (Replay.active()) return "A recording is playing; recording starts in a world.";
        synchronized (LOCK) {
            if (loginFinished == null || gameLogin == null) {
                return "Breeze joined this world before it could record it: leave and join again, then record.";
            }
        }
        try {
            Path dir = mc.gameDirectory.toPath().resolve("recordings");
            Files.createDirectories(dir);
            String base = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(new Date());
            Path f = dir.resolve(base + RecFormat.EXTENSION);
            for (int i = 1; Files.exists(f); i++) f = dir.resolve(base + "_" + i + RecFormat.EXTENSION);
            RecMeta meta = new RecMeta();
            meta.minecraft = RecGame.versionName();
            meta.protocol = RecGame.protocol();
            meta.loader = dev.breeze.compat.Platform.name();
            meta.breeze = dev.breeze.compat.Platform.modVersion("breeze");
            meta.startedAt = System.currentTimeMillis();
            meta.player = mc.getUser().getName();
            meta.world = mc.getSingleplayerServer() != null
                    ? mc.getSingleplayerServer().getWorldData().getLevelName() : "Multiplayer";
            synchronized (LOCK) {
                snapshotDone = false;
                early.clear();
                startNanos = System.nanoTime();
                file = f;
                writer = new RecWriter(f, meta);
            }
            self.reset();
            return null;
        } catch (Exception e) {
            writer = null;
            return "The recording could not be started: " + e.getMessage();
        }
    }

    /**
     * Writes how the recording begins: the connection's prelude, the world
     * as the client has it, then the packets that came in meanwhile. Runs at
     * the end of the first tick after start, so every packet Minecraft had
     * queued before recording began is already part of the world copied.
     */
    private static void begin(Minecraft mc, RecWriter w) {
        List<Rec> head = new ArrayList<>();
        synchronized (LOCK) {
            head.add(new Rec(RecFormat.Kind.PROTOCOL, new byte[]{RecNet.LOGIN}));
            head.add(new Rec(RecFormat.Kind.PACKET, loginFinished));
            head.add(new Rec(RecFormat.Kind.PROTOCOL, new byte[]{RecNet.CONFIG}));
            for (byte[] b : config) head.add(new Rec(RecFormat.Kind.PACKET, b));
            head.add(new Rec(RecFormat.Kind.PROTOCOL, new byte[]{RecNet.PLAY}));
            head.add(new Rec(RecFormat.Kind.PACKET, gameLogin));
            if (lastRespawn != null) head.add(new Rec(RecFormat.Kind.PACKET, lastRespawn));
        }
        for (Packet<?> p : Snapshot.of(mc)) {
            try {
                head.add(new Rec(RecFormat.Kind.PACKET, RecNet.encode(RecNet.PLAY, mc.getConnection(), p)));
            } catch (Throwable t) {
                BreezeClient.LOGGER.debug("[Breeze] recorder: a world packet could not be copied: {}", t.toString());
            }
        }
        for (Rec r : head) w.record(r.kind(), 0, r.data());
        synchronized (LOCK) {
            for (Rec r : early) w.record(r.kind(), 0, r.data());
            early.clear();
            snapshotDone = true;
        }
    }

    public static String stop(Minecraft mc) {
        RecWriter w;
        Path f;
        synchronized (LOCK) {
            w = writer;
            f = file;
            writer = null;
            file = null;
            snapshotDone = false;
            early.clear();
        }
        if (w == null) return null;
        long duration = (System.nanoTime() - startNanos) / 1_000_000L;
        Thread t = new Thread(() -> {
            try {
                w.close(duration);
            } catch (Exception e) {
                BreezeClient.LOGGER.warn("[Breeze] the recording could not be finished: {}", e.toString());
            }
        }, "Breeze recorder close");
        t.setDaemon(true);
        t.start();
        return f.getFileName().toString();
    }

    // ── Each tick (render thread) ──────────────────────────────────────────

    public static void tick(Minecraft mc) {
        if (afterLeaving != null && mc.level == null && !RecGame.leavingScreen(mc)) {
            Runnable r = afterLeaving;
            afterLeaving = null;
            r.run();
        }
        Replay.tick(mc);
        RecWriter w = writer;
        if (w == null) return;
        if (mc.level == null || mc.player == null) {
            String name = stop(mc);
            BreezeClient.LOGGER.info("[Breeze] left the world: recording {} saved", name);
            return;
        }
        if (!snapshotDone) begin(mc, w);
        byte[] s = self.capture(mc.player, mc);
        if (s != null) w.record(RecFormat.Kind.SELF, recordedMs(), s);
        if (w.failure() != null) {
            String why = w.failure();
            stop(mc);
            dev.breeze.shots.ScreenshotChat.say(mc, why);
        }
    }

    /** A watch or export asked for from inside a world: it starts once the world is left. */
    private static Runnable afterLeaving;

    /** Opens a recording to watch (leaving the world first if in one); null when it started, else why not. */
    public static String watch(Minecraft mc, Path file) {
        if (writer != null) return "Stop recording first.";
        if (mc.level != null) return leaveThen(mc, () -> report(mc, Replay.open(mc, file)));
        return Replay.open(mc, file);
    }

    private static String leaveThen(Minecraft mc, Runnable then) {
        afterLeaving = then;
        if (Replay.active()) {
            Replay.stop(mc);
            RecGame.toTitle(mc);
        } else {
            RecGame.leaveWorld(mc);
        }
        return null;
    }

    private static void report(Minecraft mc, String problem) {
        if (problem != null) RecGame.toast(mc, "Breeze recorder", problem);
    }

    /** Playback controls (the Playback bar): toggle, back, forward, slower, faster, leave. */
    public static void control(Minecraft mc, String what) {
        Replay r = Replay.current();
        if (r == null) return;
        Timeline tl = r.timeline();
        switch (what) {
            case "toggle" -> tl.toggle();
            case "back" -> r.seek(mc, tl.exactMs() - 10_000);
            case "forward" -> r.seek(mc, tl.exactMs() + 10_000);
            case "slower" -> tl.changeSpeed(-1);
            case "faster" -> tl.changeSpeed(1);
            case "leave" -> {
                Replay.stop(mc);
                RecGame.toTitle(mc);
            }
            default -> { }
        }
    }

    /** The recording being watched, or null. */
    public static Path watching() {
        Replay r = Replay.current();
        return r == null ? null : r.path();
    }

    /** What is playing (self-test and the playback bar): null when nothing plays. */
    public static String replayInfo() {
        Replay r = Replay.current();
        if (r == null) return null;
        return "pos=" + r.timeline().positionMs() + " length=" + r.timeline().durationMs() + " playing=" + r.timeline().playing()
                + " speed=" + r.timeline().speed() + " world=" + r.inWorld() + " fed=" + r.packetsFed() + " errors=" + r.packetErrors();
    }

    private static final java.util.concurrent.atomic.AtomicLong copied = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong copyNanos = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong copyBytes = new java.util.concurrent.atomic.AtomicLong();

    /** What recording has cost off the render thread so far (self-test). */
    public static String stats() {
        long n = copied.get();
        RecWriter w = writer;
        return "packets=" + n + " copyUsPerPacket=" + (n == 0 ? 0 : copyNanos.get() / n / 1000.0) + " copiedBytes=" + copyBytes.get()
                + (w == null ? "" : " onDisk=" + w.bytesOnDisk() + " raw=" + w.rawBytes() + " pending=" + w.pendingBytes());
    }

    /** Starts exporting a recording to a video; null when it started, else why not. */
    public static String export(Minecraft mc, Path file, int fps, VideoEncoder.Quality quality) {
        if (writer != null) return "Stop recording first.";
        if (mc.level != null) return leaveThen(mc, () -> report(mc, Exporter.start(mc, file, fps, quality)));
        return Exporter.start(mc, file, fps, quality);
    }

    public static String lastExport() {
        return Exporter.lastResult;
    }

    public static Path lastVideo() {
        return Exporter.lastVideo;
    }
}
