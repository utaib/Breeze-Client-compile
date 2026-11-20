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
 * tick, so the camera is their view, hand and HUD included.
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

    private Replay(Path path, RecReader reader, double startAt) {
        this.path = path;
        this.reader = reader;
        this.timeline = new Timeline(reader.durationMs());
        this.startAt = startAt;
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
        if (startAt > 0 && timeline.exactMs() < startAt) timeline.seek(startAt);
        else timeline.advance(realMs);
        feedUntil(mc, timeline.positionMs());
        conn.tick();
        channel.runPendingTasks();
        channel.releaseOutbound();
        if (ready) {
            if (!worldSeen) {
                worldSeen = true;
                if (pending == PAUSE_AT_START) timeline.pause();
            }
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
                case RecFormat.Kind.PROTOCOL -> phase = rec.data()[0];
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
            channel.writeInbound(p);
            fed++;
        } catch (Throwable t) {
            packetErrors++;
            if (packetErrors <= 5) BreezeClient.LOGGER.warn("[Breeze] a recorded packet could not be played: {}", t.toString());
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
