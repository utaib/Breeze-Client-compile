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
 * thread only adds the player's state once a tick and, each frame, the
 * camera's turn at that frame ({@link #frame}: ten bytes, no allocation).
 */
public final class RecorderHooks {

    private record Rec(int kind, long timeMs, byte[] data) {}

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
    private static RecMeta meta;
    private static final RecSelf self = new RecSelf();

    // The camera of every frame (render thread only).
    private static final byte[] camera = new byte[CameraFrames.SIZE];
    /** Frames seen while the world is still being copied: kept here, written after it in time order. */
    private static final int EARLY_FRAMES = 8192;
    private static final long[] earlyNanos = new long[EARLY_FRAMES];
    private static final float[] earlyYaw = new float[EARLY_FRAMES];
    private static final float[] earlyPitch = new float[EARLY_FRAMES];
    private static int earlyFrames;
    private static long frames;

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
            if (!RecNet.clientbound(connection) || Replay.isReplay(connection) || RecNet.skip(packet)) return;
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
                early.add(new Rec(kind, recordedMs(), data));
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
            RecorderHooks.meta = meta;
            meta.minecraft = RecGame.versionName();
            meta.protocol = RecGame.protocol();
            meta.loader = dev.breeze.compat.Platform.name();
            meta.breeze = dev.breeze.compat.Platform.modVersion("breeze");
            meta.startedAt = System.currentTimeMillis();
            meta.player = mc.getUser().getName();
            meta.world = mc.getSingleplayerServer() != null
                    ? mc.getSingleplayerServer().getWorldData().getLevelName() : "Multiplayer";
            listener = mc.getConnection();
            headWritten = false;
            thumbnailFor = f;
            synchronized (LOCK) {
                snapshotDone = false;
                snapshot = null;
                early.clear();
                startNanos = System.nanoTime();
                earlyFrames = 0;
                frames = 0;
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

    // ── The world copy (render thread) ─────────────────────────────────────

    /** The world copy being made (render thread only), or null before the first frame. */
    private static Snapshot snapshot;
    /** Whether the connection's prelude is written (the first thing in every recording). */
    private static boolean headWritten;
    /** The game's listener when recording started: the world copy is encoded with its registries. */
    private static PacketListener listener;
    /** Frame times, for the copy's share of each frame (an average over the last frames). */
    private static long lastFrameAt, frameNanosAvg;
    private static long copyStart, copyWorstNanos, copyTotalNanos;
    private static int copyFrames, copyDistance;
    private static String lastCopy = "";

    /**
     * How long one frame may spend on the world copy: a tenth of a frame at
     * the frame rate the game has now, at least 1.5 ms and at most 8 ms. At
     * 100 frames a second that is 1.5 ms (a chunk takes a tenth to a few
     * tenths of a millisecond on a normal computer, so render distance 12 is
     * copied in about a second); on a slow machine drawing 10 frames a second,
     * 8 ms of a 100 ms frame.
     */
    static long copyBudgetNanos() {
        long tenth = frameNanosAvg / 10;
        return Math.max(1_500_000L, Math.min(8_000_000L, tenth));
    }

    /** The connection's prelude: login, configuration, the world joined. Written once, first. */
    private static void writeHead(Minecraft mc, RecWriter w) {
        if (headWritten) return;
        headWritten = true;
        List<Rec> head = new ArrayList<>();
        synchronized (LOCK) {
            head.add(new Rec(RecFormat.Kind.PROTOCOL, 0, new byte[]{RecNet.LOGIN}));
            head.add(new Rec(RecFormat.Kind.PACKET, 0, loginFinished));
            head.add(new Rec(RecFormat.Kind.PROTOCOL, 0, new byte[]{RecNet.CONFIG}));
            for (byte[] b : config) head.add(new Rec(RecFormat.Kind.PACKET, 0, b));
            head.add(new Rec(RecFormat.Kind.PROTOCOL, 0, new byte[]{RecNet.PLAY}));
            head.add(new Rec(RecFormat.Kind.PACKET, 0, gameLogin));
            if (lastRespawn != null) head.add(new Rec(RecFormat.Kind.PACKET, 0, lastRespawn));
        }
        for (Rec r : head) w.record(r.kind(), 0, r.data());
    }

    /** One packet of the world copy, encoded and written at once. */
    private static void put(RecWriter w, Packet<?> p) {
        try {
            w.record(RecFormat.Kind.PACKET, 0, RecNet.encode(RecNet.PLAY, listener, p));
        } catch (Throwable t) {
            BreezeClient.LOGGER.debug("[Breeze] recorder: a world packet could not be copied: {}", t.toString());
        }
    }

    /**
     * This frame's share of the world copy, made after the frame is drawn:
     * the prelude first, then the world as the client has it, nearest
     * chunks first, within {@link #copyBudgetNanos}. Once it is complete,
     * the packets that came in meanwhile follow at the moments they came.
     * Every packet Minecraft had queued before recording began is already
     * part of the world copied, since ticks run before this.
     */
    private static void copyStep(Minecraft mc, RecWriter w) {
        long t0 = System.nanoTime();
        if (snapshot == null) {
            writeHead(mc, w);
            snapshot = new Snapshot(mc);
            copyStart = t0;
            copyFrames = 0;
            copyWorstNanos = 0;
            copyTotalNanos = 0;
            copyDistance = RecWorld.renderDistance(mc);
            BreezeClient.LOGGER.info("[Breeze] recorder: copying the world ({} chunk positions at render distance {})",
                    snapshot.positions(), copyDistance);
        }
        boolean done = snapshot.step(mc, p -> put(w, p), copyBudgetNanos());
        long spent = System.nanoTime() - t0;
        copyFrames++;
        copyTotalNanos += spent;
        copyWorstNanos = Math.max(copyWorstNanos, spent);
        if (done) completeCopy(w, 0);
    }

    /**
     * The copy is complete: what came in while it was made, at the moments
     * it came, with the frames seen meanwhile (both in time order, merged),
     * and from now on everything is written as it comes.
     */
    private static void completeCopy(RecWriter w, int skipped) {
        Snapshot s = snapshot;
        long ms = (System.nanoTime() - copyStart) / 1_000_000;
        lastCopy = "renderDistance=" + copyDistance + " chunks=" + (s == null ? 0 : s.chunksCopied()) + " positions=" + (s == null ? 0 : s.positions())
                + " frames=" + copyFrames + " ms=" + ms
                + " worstFrameUs=" + copyWorstNanos / 1000 + " avgFrameUs=" + (copyFrames == 0 ? 0 : copyTotalNanos / copyFrames / 1000)
                + " usPerChunk=" + (s == null ? 0 : s.nanosPerChunk() / 1000) + " skipped=" + skipped;
        BreezeClient.LOGGER.info("[Breeze] recorder: world copied ({})", lastCopy);
        snapshot = null;
        synchronized (LOCK) {
            int f = 0;
            for (Rec r : early) {
                while (f < earlyFrames && earlyNanos[f] / 1_000_000L <= r.timeMs()) writeFrame(w, earlyNanos[f], earlyYaw[f], earlyPitch[f++]);
                w.record(r.kind(), r.timeMs(), r.data());
            }
            while (f < earlyFrames) writeFrame(w, earlyNanos[f], earlyYaw[f], earlyPitch[f++]);
            earlyFrames = 0;
            early.clear();
            snapshotDone = true;
        }
    }

    /** Whether the world copy at the start of the recording is still being made (self-test). */
    public static boolean copying() {
        return writer != null && !snapshotDone;
    }

    /** How the last world copy went (self-test and the log): chunks, frames, milliseconds, frame cost. */
    public static String lastCopy() {
        return lastCopy;
    }

    // ── Each frame (render thread) ─────────────────────────────────────────

    /**
     * After each frame is drawn: the camera's turn the player saw in it, with
     * its time (CAMERA record). Watching and exporting turn the camera from
     * these, so a video at a high frame rate turns as the player did between
     * ticks too. Costs a clock read and a ten-byte copy; measured under
     * "Recorder" in the frame section (perf/FrameCost).
     */
    public static void frame(Minecraft mc) {
        long now = System.nanoTime();
        if (lastFrameAt != 0) {
            long d = Math.min(now - lastFrameAt, 1_000_000_000L);
            frameNanosAvg = frameNanosAvg == 0 ? d : frameNanosAvg + (d - frameNanosAvg) / 16;
        }
        lastFrameAt = now;
        RecFrames.poll(mc);
        RecWriter w = writer;
        if (w == null) return;
        net.minecraft.client.player.LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        boolean cost = dev.breeze.perf.FrameCost.enabled();
        long t0 = cost ? System.nanoTime() : 0, b0 = cost ? dev.breeze.perf.FrameCost.allocated() : 0;
        long at = System.nanoTime() - startNanos;
        float yaw = p.getYRot(), pitch = p.getXRot();
        if (snapshotDone) {
            writeFrame(w, at, yaw, pitch);
        } else {
            if (earlyFrames < EARLY_FRAMES) {
                earlyNanos[earlyFrames] = at;
                earlyYaw[earlyFrames] = yaw;
                earlyPitch[earlyFrames++] = pitch;
            }
            try {
                copyStep(mc, w);
            } catch (Throwable t) {
                // A world Breeze cannot copy must never break the game: the
                // recording goes on with what was copied.
                BreezeClient.LOGGER.warn("[Breeze] recorder: the world copy stopped early: {}", t.toString());
                Packet<?> start = RecWorld.loadStart();
                if (start != null) put(w, start);
                completeCopy(w, -1);
            }
        }
        if (snapshotDone && thumbnailFor != null) {
            Path f = thumbnailFor;
            thumbnailFor = null;
            thumbnail(mc, f);
        }
        if (cost) {
            dev.breeze.perf.FrameCost.module(dev.breeze.perf.FrameCost.FRAME, "Recorder", t0, b0);
            dev.breeze.perf.FrameCost.section(dev.breeze.perf.FrameCost.FRAME, t0, b0);
        }
    }

    private static void writeFrame(RecWriter w, long nanos, float yaw, float pitch) {
        CameraFrames.encode(camera, (int) (nanos / 1000L % 1000L), yaw, pitch);
        if (w.record(RecFormat.Kind.CAMERA, nanos / 1_000_000L, camera, 0, CameraFrames.SIZE)) frames++;
    }

    public static String stop(Minecraft mc) {
        RecWriter w = writer;
        if (w == null) return null;
        // Stopped while the world was still being copied: the copy is
        // completed now, so every recording can be watched.
        if (!snapshotDone) {
            try {
                if (snapshot == null) {
                    writeHead(mc, w);
                    if (mc.player != null && mc.level != null) {
                        snapshot = new Snapshot(mc);
                        copyStart = System.nanoTime();
                        copyDistance = RecWorld.renderDistance(mc);
                        copyFrames = 0;
                        copyWorstNanos = 0;
                        copyTotalNanos = 0;
                    }
                }
                int skipped = 0;
                if (snapshot != null) {
                    skipped = snapshot.finish(mc, p -> put(w, p), STOP_COPY_NANOS);
                } else {
                    // The world is gone and nothing was copied: what ends "Loading terrain" at least.
                    Packet<?> start = RecWorld.loadStart();
                    if (start != null) put(w, start);
                }
                BreezeClient.LOGGER.info("[Breeze] recorder: stopped while the world was being copied; the copy was completed");
                completeCopy(w, skipped);
                if (thumbnailFor != null && mc.level != null) thumbnail(mc, thumbnailFor);
            } catch (Throwable t) {
                BreezeClient.LOGGER.warn("[Breeze] recorder: the world copy could not be completed: {}", t.toString());
            }
        }
        thumbnailFor = null;
        Path f;
        synchronized (LOCK) {
            f = file;
            writer = null;
            file = null;
            snapshotDone = false;
            snapshot = null;
            early.clear();
        }
        long duration = (System.nanoTime() - startNanos) / 1_000_000L;
        RecMeta m = meta;
        if (m != null) {
            // The frame rate the player had, for exports "As recorded".
            m.frames = frames;
            m.fps = duration > 0 ? Math.round(frames * 10_000.0 / duration) / 10.0 : 0;
        }
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

    /** How long stopping may spend completing a world copy that was still being made. */
    private static final long STOP_COPY_NANOS = 500_000_000L;

    // ── Each tick (render thread) ──────────────────────────────────────────

    public static void tick(Minecraft mc) {
        if (afterLeaving != null && mc.level == null && !RecGame.leavingScreen(mc)) {
            Runnable r = afterLeaving;
            afterLeaving = null;
            BreezeClient.LOGGER.info("[Breeze] recorder: the world is left; starting what was asked");
            r.run();
        }
        Replay.tick(mc);
        openRequested(mc);
        RecWriter w = writer;
        if (w == null) return;
        if (mc.level == null || mc.player == null) {
            String name = stop(mc);
            BreezeClient.LOGGER.info("[Breeze] left the world: recording {} saved", name);
            return;
        }
        byte[] s = self.capture(mc.player, mc);
        if (s != null) add(RecFormat.Kind.SELF, s);
        if (w.failure() != null) {
            String why = w.failure();
            stop(mc);
            dev.breeze.shots.ScreenshotChat.say(mc, why);
        }
    }

    private static int openCheck;

    /**
     * The launcher's Recordings panel asks for a recording to be watched by
     * writing its name into recordings/.open before it starts the game; the
     * title screen picks it up (once, then the file is gone).
     */
    private static void openRequested(Minecraft mc) {
        if (mc.level != null || Replay.active() || ++openCheck % 40 != 0) return;
        if (!(dev.breeze.compat.ActiveScreen.get(mc) instanceof net.minecraft.client.gui.screens.TitleScreen)) return;
        Path req = mc.gameDirectory.toPath().resolve("recordings").resolve(".open");
        if (!Files.isRegularFile(req)) return;
        try {
            String name = Files.readString(req).trim();
            Files.deleteIfExists(req);
            if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")) return;
            Path rec = req.getParent().resolve(name);
            if (!Files.isRegularFile(rec)) return;
            BreezeClient.LOGGER.info("[Breeze] opening {} as the launcher asked", name);
            report(mc, watch(mc, rec));
        } catch (Exception e) {
            BreezeClient.LOGGER.warn("[Breeze] the launcher's recording could not be opened: {}", e.toString());
        }
    }

    /** The recording whose thumbnail is still to be taken (once, after the world copy), or null. */
    private static Path thumbnailFor;

    /**
     * A small picture of the recording's first frame after the world copy,
     * for the launcher's list (recordings/.thumbs/<name>.jpg). Taken once:
     * the frame is read back without waiting for the graphics card
     * (RecFrames.readLater), sampled down when it arrives a frame or two
     * later, and written on its own thread.
     */
    private static void thumbnail(Minecraft mc, Path rec) {
        try {
            Path out = rec.getParent().resolve(".thumbs").resolve(rec.getFileName() + ".jpg");
            RecFrames.readLater(mc, 320, (px, w, h, bottomUp) -> {
                if (px == null || w <= 0 || h <= 0) return;
                int tw = 320, th = Math.max(1, Math.round(320f * h / w));
                int[] rgb = new int[tw * th];
                for (int y = 0; y < th; y++) {
                    int sy = y * h / th;
                    if (bottomUp) sy = h - 1 - sy;
                    for (int x = 0; x < tw; x++) {
                        int i = (sy * w + x * w / tw) * 4;
                        rgb[y * tw + x] = (px.get(i) & 0xFF) << 16 | (px.get(i + 1) & 0xFF) << 8 | (px.get(i + 2) & 0xFF);
                    }
                }
                Thread t = new Thread(() -> {
                    try {
                        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(tw, th, java.awt.image.BufferedImage.TYPE_INT_RGB);
                        img.setRGB(0, 0, tw, th, rgb, 0, tw);
                        Files.createDirectories(out.getParent());
                        javax.imageio.ImageIO.write(img, "jpg", out.toFile());
                    } catch (Throwable ignored) {
                        // No thumbnail: the launcher shows the recording without one.
                    }
                }, "Breeze recording thumbnail");
                t.setDaemon(true);
                t.start();
            });
        } catch (Throwable ignored) {
            // A thumbnail is never worth a failed recording.
        }
    }

    /** A watch or export asked for from inside a world: it starts once the world is left. */
    private static Runnable afterLeaving;

    /** Opens a recording to watch (leaving the world first if in one); null when it started, else why not. */
    public static String watch(Minecraft mc, Path file) {
        if (writer != null) return "Stop recording first.";
        Replay.lastProblem = null;
        if (mc.level != null) return leaveThen(mc, () -> report(mc, Replay.open(mc, file)));
        return Replay.open(mc, file);
    }

    private static String leaveThen(Minecraft mc, Runnable then) {
        afterLeaving = then;
        BreezeClient.LOGGER.info("[Breeze] recorder: leaving the world first (a singleplayer world is saved, which can take a while)");
        if (Replay.active()) {
            Replay.stop(mc);
            RecGame.toTitle(mc);
        } else {
            RecGame.leaveWorld(mc);
        }
        return null;
    }

    private static void report(Minecraft mc, String problem) {
        if (problem == null) return;
        BreezeClient.LOGGER.warn("[Breeze] recorder: {}", problem);
        RecGame.toast(mc, "Breeze recorder", problem);
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
            case "camera" -> r.toggleFreeCamera(mc);
            case "leave" -> {
                Replay.stop(mc);
                RecGame.toTitle(mc);
            }
            default -> { }
        }
    }

    /** Why the last watch was given up (its world never came on screen), or null (self-test). */
    public static String lastWatchProblem() {
        return Replay.lastProblem;
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
                + " speed=" + r.timeline().speed() + " camera=" + (r.freeCamera() ? "free" : "player") + " world=" + r.inWorld() + " fed=" + r.packetsFed() + " errors=" + r.packetErrors();
    }

    private static final java.util.concurrent.atomic.AtomicLong copied = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong copyNanos = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong copyBytes = new java.util.concurrent.atomic.AtomicLong();

    /** What recording has cost off the render thread so far (self-test). */
    public static String stats() {
        long n = copied.get();
        RecWriter w = writer;
        return "worldCopy=" + lastCopy.replace(' ', ',') + " packets=" + n + " copyUsPerPacket=" + (n == 0 ? 0 : copyNanos.get() / n / 1000.0) + " copiedBytes=" + copyBytes.get()
                + (w == null ? "" : " onDisk=" + w.bytesOnDisk() + " raw=" + w.rawBytes() + " pending=" + w.pendingBytes());
    }

    /** Starts exporting a recording to a video with the export settings' size; null when it started, else why not. */
    public static String export(Minecraft mc, Path file, int fps, VideoEncoder.Quality quality) {
        return export(mc, file, fps, ExportSettings.get(mc.gameDirectory.toPath()).size, quality);
    }

    /**
     * The same at a size (a video height, 0 for the window's) and frame rate
     * (ExportSettings.AS_RECORDED for the player's own while recording).
     */
    public static String export(Minecraft mc, Path file, int fps, int size, VideoEncoder.Quality quality) {
        if (writer != null) return "Stop recording first.";
        if (mc.level != null) return leaveThen(mc, () -> report(mc, Exporter.start(mc, file, fps, size, quality)));
        return Exporter.start(mc, file, fps, size, quality);
    }

    public static String lastExport() {
        return Exporter.lastResult;
    }

    public static Path lastVideo() {
        return Exporter.lastVideo;
    }
}
