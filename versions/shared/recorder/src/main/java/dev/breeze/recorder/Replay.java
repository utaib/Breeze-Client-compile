package dev.breeze.recorder;

import dev.breeze.BreezeClient;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;

import java.nio.file.Path;
import java.util.List;

/**
 * Watching a recording: the recording becomes a world
 * the game connects to. Its packets are handed to a connection of the
 * game's own, set up the way singleplayer sets up its in-memory one, so
 * Minecraft builds the world and draws it exactly as it would have, under
 * whatever resource packs and shaders are on now. What the client sends
 * back goes nowhere. The player's own recorded state moves the player each
 * tick, so the camera is their view, hand and HUD included; the camera's
 * turn is set again before every frame from the frames the player saw
 * (CameraTrack), at that frame's exact moment.
 *
 * The playback clock is {@link Timeline}; seeking forward feeds packets
 * quickly, seeking back starts the recording again and feeds up to there.
 */
public final class Replay {

    private static volatile Replay current;

    private final Path path;
    private final RecReader reader;
    private final Timeline timeline;
    private final Connection conn;
    private final EmbeddedChannel channel;
    private int chunk = -1;
    private List<RecReader.Rec> recs = List.of();
    private int next;
    private int phase = RecNet.LOGIN;
    private long lastNanos;
    private boolean worldSeen;
    private RecSelf.State self;
    private long fed;
    private long packetErrors;
    /** Where to go to once open (a seek back reopens the recording there). */
    private final double startAt;
    /** The camera of every recorded frame (or of every tick, in older recordings). */
    private final CameraTrack camera;
    private final float[] turn = new float[2];

    // What the watch has been through, for the log and the fallbacks below.
    private final long openedAt = System.nanoTime();
    private boolean joinedLogged, headFed, loadStartSeen, positionSeen, centerSeen, loadStartAdded, placed, endLogged;
    private int centerX, centerZ;
    private long headFedAt, loadingSince;

    private Replay(Path path, RecReader reader, double startAt) {
        this.path = path;
        this.reader = reader;
        this.timeline = new Timeline(reader.durationMs());
        this.startAt = startAt;
        this.camera = new CameraTrack(reader);
        channel = new EmbeddedChannel();
        conn = RecNet.replayConnection(channel);
    }

    static boolean isReplay(Connection c) {
        Replay r = current;
        return r != null && r.conn == c;
    }

    public static boolean active() {
        return current != null;
    }

    public static Replay current() {
        return current;
    }

    public Timeline timeline() {
        return timeline;
    }

    public Path path() {
        return path;
    }

    public long packetsFed() {
        return fed;
    }

    public long packetErrors() {
        return packetErrors;
    }

    /** Whether the recorded world has loaded and the player is in it. */
    public boolean inWorld() {
        return worldSeen;
    }

    /** Opens a recording to watch; null when it opened, else why not. Call outside a world, on the render thread. */
    public static String open(Minecraft mc, Path file) {
        return open(mc, file, 0);
    }

    private static String open(Minecraft mc, Path file, double at) {
        if (current != null) return "A recording is already playing.";
        if (mc.level != null) return "Leave the world first.";
        RecReader rd;
        try {
            rd = new RecReader(file);
        } catch (Exception e) {
            return "That recording could not be read: " + e.getMessage();
        }
        String here = RecGame.versionName();
        if (!here.equals(rd.meta().minecraft)) {
            close(rd);
            return "This recording was made on Minecraft " + rd.meta().minecraft + "; open it on that version to watch it.";
        }
        try {
            Replay r = new Replay(file, rd, at);
            // Exactly Minecraft's own start of a connection: the handshake, then
            // both directions in the login phase (what the client answers must
            // be encodable, though it goes nowhere).
            RecNet.startLogin(mc, r.conn);
            r.channel.runPendingTasks();
            r.channel.releaseOutbound();
            r.lastNanos = Exporter.nanos();
            current = r;
            r.timeline.play();
            BreezeClient.LOGGER.info("[Breeze] watching {}: Minecraft {}, {} ms, {} parts, {} bytes", file.getFileName(),
                    rd.meta().minecraft, rd.durationMs(), rd.chunks().size(), java.nio.file.Files.size(file));
            return null;
        } catch (Throwable t) {
            close(rd);
            BreezeClient.LOGGER.warn("[Breeze] could not open recording {}", file, t);
            return "That recording could not be opened: " + t;
        }
    }

    private static void close(RecReader rd) {
        try {
            rd.close();
        } catch (Exception ignored) {}
    }

    /** Stops watching (leaving the world closes it too). */
    public static void stop(Minecraft mc) {
        Replay r = current;
        if (r == null) return;
        current = null;
        BreezeClient.LOGGER.info("[Breeze] stopped watching {} at {} ms", r.path.getFileName(), r.timeline.positionMs());
        close(r.reader);
        try {
            r.channel.close();
        } catch (Throwable ignored) {}
    }

    /** Jumps to a moment: forward by feeding, back by opening the recording again there. */
    public void seek(Minecraft mc, double ms) {
        if (ms >= timeline.exactMs()) {
            timeline.seek(ms);
            return;
        }
        Path p = path;
        boolean playing = timeline.playing();
        double speed = timeline.speed();
        RecGame.toTitle(mc);
        stop(mc);
        open(mc, p, Math.max(0, ms));
        Replay r = current;
        if (r != null) {
            r.timeline.setSpeed(speed);
            if (!playing) r.pending = PAUSE_AT_START;
        }
    }

    private static final int PAUSE_AT_START = 1;
    private static final double MAX_TICK_MS = 250;

    /** Free camera: the viewer flies where they like; off, the camera is the recorded player's own view. */
    private boolean freeCamera;
    /** The inventory screen the recording opened (closed again when the recording closes it). */
    private net.minecraft.client.gui.screens.Screen openedScreen;

    public boolean freeCamera() {
        return freeCamera;
    }

    /** Switches between the player's own view and a free camera. */
    public void toggleFreeCamera(Minecraft mc) {
        freeCamera = !freeCamera;
        LocalPlayer p = mc.player;
        if (p == null) return;
        if (freeCamera) {
            if (openedScreen != null && dev.breeze.compat.ActiveScreen.get(mc) == openedScreen) dev.breeze.compat.ActiveScreen.set(mc, null);
            openedScreen = null;
            p.getAbilities().mayfly = true;
            p.getAbilities().flying = true;
        } else {
            RecSelf.State st = self;
            p.getAbilities().flying = st != null && (st.flags() & RecSelf.FLYING) != 0;
        }
    }
    private int pending;

    // ── Each client tick ───────────────────────────────────────────────────

    static void tick(Minecraft mc) {
        Replay r = current;
        if (r == null) return;
        try {
            r.step(mc);
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] the recording stopped playing", t);
            stop(mc);
        }
    }

    private void step(Minecraft mc) {
        if (worldSeen && mc.level == null) {
            // The player left the recorded world (Disconnect in the pause menu).
            stop(mc);
            return;
        }
        long now = Exporter.nanos();
        double realMs = (now - lastNanos) / 1_000_000.0;
        lastNanos = now;
        // The clock waits at the start until the recorded world is on screen
        // (its first chunks drawn, no loading screen), so nothing is missed.
        boolean ready = mc.level != null && mc.player != null && dev.breeze.compat.ActiveScreen.get(mc) == null;
        if (!ready && !worldSeen) realMs = 0;
        // A frame that hangs (shaders compiling when the world opens, a
        // stall) holds the recording there rather than jumping past what
        // the viewer never saw: at most a quarter of a second per tick.
        if (realMs > MAX_TICK_MS) realMs = MAX_TICK_MS;
        if (startAt > 0 && timeline.exactMs() < startAt) timeline.seek(startAt);
        else timeline.advance(realMs);
        feedUntil(mc, timeline.positionMs());
        conn.tick();
        channel.runPendingTasks();
        channel.releaseOutbound();
        if (!worldSeen) loading(mc, now);
        if (ready) {
            if (!worldSeen) {
                worldSeen = true;
                if (pending == PAUSE_AT_START) timeline.pause();
                BreezeClient.LOGGER.info("[Breeze] watching: the recorded world is on screen after {} ms; playing", (now - openedAt) / 1_000_000);
                if (!positionSeen) {
                    dev.breeze.shots.ScreenshotChat.say(mc, "This recording was saved while Breeze was still copying the world"
                            + " (Breeze 2.30.0), so it shows the place it was made but not what happened there.");
                }
            }
        }
        if (worldSeen && !endLogged && timeline.positionMs() >= timeline.durationMs()) {
            endLogged = true;
            BreezeClient.LOGGER.info("[Breeze] watching: reached the end ({} ms, {} packets, {} not played)", timeline.durationMs(), fed, packetErrors);
        }
        if (mc.level != null && mc.player != null) {
            if (freeCamera) {
                // Flying, moved by the viewer's own keys and mouse.
                mc.player.getAbilities().mayfly = true;
                mc.player.getAbilities().flying = true;
            } else {
                applySelf(mc.player);
                showRecordedScreen(mc);
            }
        }
    }

    /** How long a watch may take to show the recorded world before Breeze gives up on it. */
    private static final long GIVE_UP_NANOS = 45_000_000_000L;

    /**
     * Until the recorded world is on screen: logs each stage, and sees to it
     * that "Loading terrain" always ends. A recording stopped while Breeze
     * 2.30.0 was still copying the world has no player position and no
     * packet that ends the loading screen (1.20.3 and later wait for it);
     * once the copied world is in, Breeze puts the player in its middle,
     * adds that packet itself and, if the screen still stays, closes it. A
     * recording whose world never loads is closed with a plain message.
     */
    private void loading(Minecraft mc, long now) {
        boolean inLevel = mc.level != null && mc.player != null;
        if (inLevel && !joinedLogged) {
            joinedLogged = true;
            BreezeClient.LOGGER.info("[Breeze] watching: joined the recorded world after {} ms", (now - openedAt) / 1_000_000);
        }
        if (inLevel && !headFed) {
            headFed = true;
            headFedAt = now;
            BreezeClient.LOGGER.info("[Breeze] watching: the recorded world is in ({} packets{}{})", fed,
                    positionSeen ? "" : ", no player position", loadStartSeen || RecWorld.loadStart() == null ? "" : ", no load-start packet");
        }
        if (headFed && inLevel) {
            if (!positionSeen && !placed) {
                placed = true;
                placeInMiddle(mc);
            }
            if (!loadStartSeen && !loadStartAdded) {
                loadStartAdded = true;
                Packet<?> start = RecWorld.loadStart();
                if (start != null) {
                    BreezeClient.LOGGER.info("[Breeze] watching: the recording has no packet that ends Loading terrain; Breeze adds it");
                    try {
                        channel.writeInbound(start);
                        channel.releaseOutbound();
                    } catch (Throwable t) {
                        BreezeClient.LOGGER.warn("[Breeze] watching: the load-start packet could not be played: {}", t.toString());
                    }
                }
            }
            if (dev.breeze.compat.ActiveScreen.get(mc) != null) {
                if (loadingSince == 0) loadingSince = now;
                boolean whole = positionSeen && (loadStartSeen || RecWorld.loadStart() == null);
                long wait = whole ? 15_000_000_000L : 3_000_000_000L;
                if (now - loadingSince > wait) {
                    BreezeClient.LOGGER.info("[Breeze] watching: the loading screen ({}) stayed {} ms after the world was in; Breeze closes it",
                            dev.breeze.compat.ActiveScreen.get(mc).getClass().getSimpleName(), (now - loadingSince) / 1_000_000);
                    dev.breeze.compat.ActiveScreen.set(mc, null);
                    loadingSince = 0;
                }
            }
        }
        if (now - openedAt > GIVE_UP_NANOS) {
            String why = !inLevel ? "the recorded world never loaded" : "the recorded world never came on screen";
            BreezeClient.LOGGER.warn("[Breeze] watching {}: {} in {} s ({} packets fed, {} not played); closing it",
                    path.getFileName(), why, GIVE_UP_NANOS / 1_000_000_000L, fed, packetErrors);
            lastProblem = "This recording could not be played: " + why + ".";
            stop(mc);
            RecGame.toTitle(mc);
            RecGame.toast(mc, "Breeze recorder", lastProblem);
        }
    }

    /** Why the last watch was given up, or null (self-test). */
    static volatile String lastProblem;

    /** The player in the middle of the copied world, on its ground (no position was recorded). */
    private void placeInMiddle(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || !centerSeen) return;
        int x = centerX * 16 + 8, z = centerZ * 16 + 8;
        int y = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
        p.setPos(x + 0.5, y + 1, z + 0.5);
        BreezeClient.LOGGER.info("[Breeze] watching: no player position in the recording; the player is put at {} {} {}", x, y + 1, z);
    }

    /** Notes the packets the fallbacks in {@link #loading} look for. */
    private void note(Packet<?> p) {
        if (p instanceof net.minecraft.network.protocol.game.ClientboundGameEventPacket g) {
            if (loadStartType == null) {
                Packet<?> start = RecWorld.loadStart();
                loadStartType = start instanceof net.minecraft.network.protocol.game.ClientboundGameEventPacket s ? s.getEvent() : NONE;
            }
            if (g.getEvent() == loadStartType) loadStartSeen = true;
        } else if (p instanceof net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket) {
            positionSeen = true;
        } else if (p instanceof net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket c) {
            centerX = c.getX();
            centerZ = c.getZ();
            centerSeen = true;
        }
    }

    private static final Object NONE = new Object();
    private static Object loadStartType;

    // ── Each frame, before it is drawn ─────────────────────────────────────

    /**
     * Turns the camera to where the player looked at this frame's moment of
     * the recording: between ticks the moment moves on with the clock (the
     * video's while exporting), so every drawn frame has its own turn. Both
     * the turn and the turn "before" are set, so it holds on versions that
     * blend the two by the frame's fraction of a tick.
     */
    public static void beforeFrame(Minecraft mc) {
        Replay r = current;
        if (r == null || r.freeCamera || !r.worldSeen || r.self == null) return;
        LocalPlayer p = mc.player;
        if (p == null) return;
        try {
            if (!r.camera.at(r.frameMs(), r.turn)) return;
            p.setYRot(r.turn[0]);
            p.setXRot(r.turn[1]);
            p.yRotO = r.turn[0];
            p.xRotO = r.turn[1];
        } catch (Throwable t) {
            BreezeClient.LOGGER.debug("[Breeze] the recorded camera could not be read: {}", t.toString());
        }
    }

    /** This frame's moment: the timeline's, moved on by the time since its last tick. */
    double frameMs() {
        double ms = timeline.exactMs();
        if (timeline.playing()) {
            double since = (Exporter.nanos() - lastNanos) / 1_000_000.0;
            ms += Math.max(0, Math.min(MAX_TICK_MS, since)) * timeline.speed();
        }
        return Math.min(ms, timeline.durationMs());
    }

    /** Whether the camera comes from the frames the player saw here (self-test). */
    public boolean cameraFromFrames() {
        return camera.fromFrames();
    }

    private void feedUntil(Minecraft mc, long untilMs) {
        while (true) {
            if (next >= recs.size()) {
                if (chunk + 1 >= reader.chunks().size()) return;
                if (reader.chunks().get(chunk + 1).firstMs() > untilMs) return;
                chunk++;
                try {
                    recs = reader.readChunk(reader.chunks().get(chunk));
                } catch (Exception e) {
                    BreezeClient.LOGGER.warn("[Breeze] a part of the recording could not be read: {}", e.toString());
                    recs = List.of();
                }
                next = 0;
                continue;
            }
            RecReader.Rec rec = recs.get(next);
            if (rec.timeMs() > untilMs) return;
            next++;
            switch (rec.kind()) {
                case RecFormat.Kind.PROTOCOL -> {
                    phase = rec.data()[0];
                    BreezeClient.LOGGER.info("[Breeze] watching: {} phase", phase == RecNet.LOGIN ? "login" : phase == RecNet.CONFIG ? "configuration" : "play");
                }
                case RecFormat.Kind.PACKET -> feed(rec.data());
                case RecFormat.Kind.SELF -> {
                    RecSelf.State s = RecSelf.State.read(rec.data());
                    if (s != null) {
                        self = s;
                        swing(mc.player, s);
                    }
                }
                default -> { }
            }
        }
    }

    private void feed(byte[] data) {
        try {
            Packet<?> p = RecNet.decode(phase, conn.getPacketListener(), data);
            note(p);
            channel.writeInbound(p);
            fed++;
        } catch (Throwable t) {
            packetErrors++;
            if (packetErrors <= 5) {
                String why = RecNet.why(conn);
                BreezeClient.LOGGER.warn("[Breeze] a recorded packet could not be played: {}{}", t.toString(),
                        why.isEmpty() ? "" : " (connection closed: " + why + ")");
            }
        }
        // Whatever the client answered goes nowhere.
        channel.releaseOutbound();
    }

    private static void swing(LocalPlayer p, RecSelf.State s) {
        if (p == null) return;
        if ((s.flags() & RecSelf.SWING_MAIN) != 0) RecPlayer.swing(p, false);
        if ((s.flags() & RecSelf.SWING_OFF) != 0) RecPlayer.swing(p, true);
    }

    /** The player's inventory opens and closes as it did while recording (other screens are not replayed). */
    private void showRecordedScreen(Minecraft mc) {
        RecSelf.State s = self;
        if (s == null || mc.player == null || !worldSeen) return;
        net.minecraft.client.gui.screens.Screen now = dev.breeze.compat.ActiveScreen.get(mc);
        if (s.screen() == RecSelf.SCREEN_INVENTORY) {
            if (now == null) {
                openedScreen = new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player);
                dev.breeze.compat.ActiveScreen.set(mc, openedScreen);
            }
        } else if (openedScreen != null) {
            if (now == openedScreen) dev.breeze.compat.ActiveScreen.set(mc, null);
            openedScreen = null;
        }
    }

    /** Puts the player where the recording says, keeping the last place so the frames between ticks glide. */
    private void applySelf(LocalPlayer p) {
        RecSelf.State s = self;
        if (s == null) return;
        p.xo = p.getX();
        p.yo = p.getY();
        p.zo = p.getZ();
        p.yRotO = p.getYRot();
        p.xRotO = p.getXRot();
        p.yHeadRotO = p.getYHeadRot();
        p.yBodyRotO = p.yBodyRot;
        p.setPos(s.x(), s.y(), s.z());
        p.setYRot(s.yRot());
        p.setXRot(s.xRot());
        p.setYHeadRot(s.headRot());
        p.yBodyRot = s.bodyRot();
        p.setDeltaMovement(0, 0, 0);
        p.setShiftKeyDown((s.flags() & RecSelf.CROUCHING) != 0);
        p.setSprinting((s.flags() & RecSelf.SPRINTING) != 0);
        if (s.slot() >= 0 && s.slot() < 9) RecPlayer.setSlot(p, s.slot());
    }
}
