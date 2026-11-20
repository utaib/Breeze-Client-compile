package dev.breeze.recorder;

import com.mojang.datafixers.util.Pair;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The world as the client has it when recording starts, as the packets a
 * server would send a player joining it now (made over the first ticks of
 * the recording, as many chunks each frame as fit in a small time budget): where the player is,
 * the time and weather, their health, food, experience and inventory, every
 * loaded chunk, and every entity with its data, equipment and riders.
 *
 * Other players are not in it yet: the game only adds a player it has tab
 * list details for, and those come from the server in a form the client does
 * not keep. A recording started in a world with other players shows them
 * from their next appearance (respawn, coming into view) on. UNVERIFIED for
 * multiplayer: the self-test records in singleplayer.
 */
final class Snapshot {

    private enum Stage { HEAD, CHUNKS, PLAYER, ENTITIES, DONE }

    /** Chunk positions to copy, nearest first (x and z packed). */
    private final long[] chunks;
    private final int cx, cz;
    private int next;
    private Stage stage = Stage.HEAD;
    private int chunksCopied;

    /** The world copy for the player's position now; step() makes it a share at a time. */
    Snapshot(Minecraft mc) {
        LocalPlayer p = mc.player;
        cx = RecWorld.centerX(p);
        cz = RecWorld.centerZ(p);
        int r = RecWorld.renderDistance(mc) + 1;
        List<long[]> all = new ArrayList<>();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                all.add(new long[]{(long) (x - cx) * (x - cx) + (long) (z - cz) * (z - cz), ((long) x << 32) | (z & 0xFFFFFFFFL)});
            }
        }
        all.sort((a, b) -> Long.compare(a[0], b[0]));
        chunks = new long[all.size()];
        for (int i = 0; i < chunks.length; i++) chunks[i] = all.get(i)[1];
    }

    int chunksCopied() {
        return chunksCopied;
    }

    /** Chunk positions looked at so far, and in all (the self-test and the log). */
    int positionsDone() {
        return next;
    }

    int positions() {
        return chunks.length;
    }

    /** Whether the chunks are all copied (only the rest, a few packets, is left). */
    boolean chunksDone() {
        return stage != Stage.HEAD && stage != Stage.CHUNKS;
    }

    /** The longest single piece of the copy so far (one chunk, the player, one entity), in nanoseconds. */
    long worstPieceNanos() {
        return worstPiece;
    }

    /** Entities copied. */
    int entitiesCopied() {
        return entitiesCopied;
    }

    private long worstPiece, worstPieceCpu;

    /** The processor time of the longest piece (the rest of its time went to other threads or the collector). */
    long worstPieceCpuNanos() {
        return worstPieceCpu;
    }

    private void piece(long wallStart, long cpuStart) {
        long took = System.nanoTime() - wallStart;
        if (took > worstPiece) {
            worstPiece = took;
            worstPieceCpu = cpuStart < 0 ? -1 : RecorderHooks.cpuNanos() - cpuStart;
        }
    }
    private int entitiesCopied;
    /** The entities to copy (taken once the chunks are done), the next one, and those carrying riders. */
    private Entity[] entities;
    private int nextEntity;
    private final List<Entity> riders = new ArrayList<>();

    /** What one chunk has cost on average so far (nanoseconds, 0 before the first). */
    long nanosPerChunk() {
        return chunksCopied == 0 ? 0 : chunkNanos / chunksCopied;
    }

    private long chunkNanos;
    private final List<Packet<?>> buf = new ArrayList<>();

    /**
     * Adds a share of the copy to out, stopping before a chunk that would
     * take it past the time budget (judged by what a chunk has cost so far;
     * at least one chunk a call); true when the copy is complete. Called
     * once a frame, so a large world is copied quickly at a high frame rate
     * without any frame stalling, and the memory it takes stays at a few
     * chunks at a time.
     */
    boolean step(Minecraft mc, Consumer<Packet<?>> out, long budgetNanos) {
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (p == null || level == null) return true;
        long until = System.nanoTime() + budgetNanos;
        head(mc, level, out);
        if (stage == Stage.CHUNKS) {
            chunks(level, out, until, true);
            if (next < chunks.length) return false;
            stage = Stage.PLAYER;
            // The rest from the next call, so this one stays within its budget.
            return false;
        }
        rest(p, level, out, until);
        return stage == Stage.DONE;
    }

    /**
     * Completes the copy at once (the recording stops while it is still
     * being made): the chunks left, as many as fit in the time given
     * (nearest first; the farthest are left out when a huge world does not
     * fit), then the rest, so the recording always has the player's place
     * and the packet that ends "Loading terrain". With the world already
     * gone (the player left), only that packet. Returns the chunks left out.
     */
    int finish(Minecraft mc, Consumer<Packet<?>> out, long budgetNanos) {
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        int skipped = 0;
        if (p != null && level != null) {
            head(mc, level, out);
            if (stage == Stage.CHUNKS) {
                chunks(level, out, System.nanoTime() + budgetNanos, false);
                skipped = chunks.length - next;
                stage = Stage.PLAYER;
            }
            rest(p, level, out, Long.MAX_VALUE);
        } else if (stage != Stage.DONE) {
            Packet<?> start = RecWorld.loadStart();
            if (start != null) out.accept(start);
        }
        stage = Stage.DONE;
        return skipped;
    }

    private void head(Minecraft mc, ClientLevel level, Consumer<Packet<?>> out) {
        if (stage != Stage.HEAD) return;
        out.accept(new ClientboundSetChunkCacheCenterPacket(cx, cz));
        out.accept(RecWorld.time(level));
        if (level.isRaining()) {
            out.accept(new ClientboundGameEventPacket(ClientboundGameEventPacket.START_RAINING, 0));
            out.accept(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, level.getRainLevel(1f)));
            out.accept(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, level.getThunderLevel(1f)));
        }
        if (mc.gameMode != null) {
            out.accept(new ClientboundGameEventPacket(ClientboundGameEventPacket.CHANGE_GAME_MODE, mc.gameMode.getPlayerMode().getId()));
        }
        stage = Stage.CHUNKS;
    }

    /** Chunks: every one the client holds within its view distance, nearest first, until the time is up. */
    private void chunks(ClientLevel level, Consumer<Packet<?>> out, long until, boolean atLeastOne) {
        boolean first = atLeastOne;
        while (next < chunks.length) {
            long now = System.nanoTime();
            if (!first && now + nanosPerChunk() > until) return;
            long cpu0 = RecorderHooks.cpuNanos();
            long c = chunks[next++];
            LevelChunk chunk = RecWorld.chunk(level, (int) (c >> 32), (int) c);
            if (chunk == null) continue;
            RecWorld.chunkPackets(buf, chunk, level);
            for (Packet<?> pk : buf) out.accept(pk);
            buf.clear();
            chunksCopied++;
            chunkNanos += System.nanoTime() - now;
            piece(now, cpu0);
            first = false;
        }
    }

    /**
     * The rest after the chunks, within the time left: the player (where
     * they are, health, food, experience, inventory, held slot, their own
     * data), then the entities a few at a time (each with its data,
     * equipment and riders), then the packet that ends "Loading terrain".
     * An entity that goes away meanwhile is still copied (its removal was
     * recorded and follows); one that comes meanwhile came in a packet.
     */
    private void rest(LocalPlayer p, ClientLevel level, Consumer<Packet<?>> out, long until) {
        if (stage == Stage.PLAYER) {
            long t0 = System.nanoTime(), cpu0 = RecorderHooks.cpuNanos();
            out.accept(RecWorld.position(p));
            out.accept(new ClientboundSetHealthPacket(p.getHealth(), p.getFoodData().getFoodLevel(), p.getFoodData().getSaturationLevel()));
            out.accept(new ClientboundSetExperiencePacket(p.experienceProgress, p.totalExperience, p.experienceLevel));
            out.accept(RecWorld.inventory(p));
            out.accept(RecWorld.heldSlot(RecPlayer.slot(p)));
            Packet<?> own = RecWorld.entityData(p);
            if (own != null) out.accept(own);
            List<Entity> all = new ArrayList<>();
            for (Entity e : level.entitiesForRendering()) {
                if (e != p && !(e instanceof Player)) all.add(e);
            }
            entities = all.toArray(new Entity[0]);
            nextEntity = 0;
            stage = Stage.ENTITIES;
            piece(t0, cpu0);
        }
        if (stage == Stage.ENTITIES) {
            while (nextEntity < entities.length) {
                long t0 = System.nanoTime();
                if (t0 > until) return;
                long cpu0 = RecorderHooks.cpuNanos();
                Entity e = entities[nextEntity];
                entities[nextEntity++] = null;
                try {
                    entity(e, out);
                } catch (Throwable t) {
                    // One entity Breeze cannot copy leaves the rest.
                }
                entitiesCopied++;
                piece(t0, cpu0);
            }
            for (Entity e : riders) out.accept(new ClientboundSetPassengersPacket(e));
            riders.clear();
            // What a server says once a joining player's first chunks are sent:
            // the client's "Loading terrain" waits for it (1.20.3 and later).
            Packet<?> start = RecWorld.loadStart();
            if (start != null) out.accept(start);
            stage = Stage.DONE;
        }
    }

    private void entity(Entity e, Consumer<Packet<?>> out) {
        RecWorld.addEntity(buf, e);
        for (Packet<?> pk : buf) out.accept(pk);
        buf.clear();
        Packet<?> data = RecWorld.entityData(e);
        if (data != null) out.accept(data);
        if (e instanceof LivingEntity le) {
            List<Pair<EquipmentSlot, ItemStack>> gear = new ArrayList<>();
            for (EquipmentSlot s : EquipmentSlot.values()) {
                ItemStack st = le.getItemBySlot(s);
                if (!st.isEmpty()) gear.add(Pair.of(s, st.copy()));
            }
            if (!gear.isEmpty()) out.accept(new ClientboundSetEquipmentPacket(e.getId(), gear));
        }
        if (!e.getPassengers().isEmpty()) riders.add(e);
    }
}
