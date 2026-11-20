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

/**
 * The world as the client has it when recording starts, as the packets a
 * server would send a player joining it now (made over the first ticks of
 * the recording, a few chunks each tick): where the player is,
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

    private enum Stage { HEAD, CHUNKS, REST, DONE }

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

    /**
     * Adds this tick's share of the copy to out, stopping once the time
     * budget is spent (at least one chunk a tick); true when the copy is
     * complete. Spreading it keeps a frame from stalling while a large world
     * is copied, and the memory it takes to a few chunks at a time.
     */
    boolean step(Minecraft mc, List<Packet<?>> out, long budgetNanos) {
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (p == null || level == null) return true;
        long until = System.nanoTime() + budgetNanos;
        if (stage == Stage.HEAD) {
            out.add(new ClientboundSetChunkCacheCenterPacket(cx, cz));
            out.add(RecWorld.time(level));
            if (level.isRaining()) {
                out.add(new ClientboundGameEventPacket(ClientboundGameEventPacket.START_RAINING, 0));
                out.add(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, level.getRainLevel(1f)));
                out.add(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, level.getThunderLevel(1f)));
            }
            if (mc.gameMode != null) {
                out.add(new ClientboundGameEventPacket(ClientboundGameEventPacket.CHANGE_GAME_MODE, mc.gameMode.getPlayerMode().getId()));
            }
            stage = Stage.CHUNKS;
        }
        if (stage == Stage.CHUNKS) {
            // Chunks: every one the client holds within its view distance, nearest first.
            boolean first = true;
            while (next < chunks.length && (first || System.nanoTime() < until)) {
                long c = chunks[next++];
                LevelChunk chunk = RecWorld.chunk(level, (int) (c >> 32), (int) c);
                if (chunk == null) continue;
                RecWorld.chunkPackets(out, chunk, level);
                chunksCopied++;
                first = false;
            }
            if (next < chunks.length) return false;
            stage = Stage.REST;
            return false;
        }
        if (stage == Stage.REST) {
            rest(mc, p, level, out);
            stage = Stage.DONE;
        }
        return true;
    }

    private static void rest(Minecraft mc, LocalPlayer p, ClientLevel level, List<Packet<?>> out) {
        out.add(RecWorld.position(p));
        out.add(new ClientboundSetHealthPacket(p.getHealth(), p.getFoodData().getFoodLevel(), p.getFoodData().getSaturationLevel()));
        out.add(new ClientboundSetExperiencePacket(p.experienceProgress, p.totalExperience, p.experienceLevel));
        out.add(RecWorld.inventory(p));
        out.add(RecWorld.heldSlot(RecPlayer.slot(p)));
        Packet<?> own = RecWorld.entityData(p);
        if (own != null) out.add(own);

        List<Entity> riders = new ArrayList<>();
        for (Entity e : level.entitiesForRendering()) {
            if (e == p || e instanceof Player) continue;
            RecWorld.addEntity(out, e);
            Packet<?> data = RecWorld.entityData(e);
            if (data != null) out.add(data);
            if (e instanceof LivingEntity le) {
                List<Pair<EquipmentSlot, ItemStack>> gear = new ArrayList<>();
                for (EquipmentSlot s : EquipmentSlot.values()) {
                    ItemStack st = le.getItemBySlot(s);
                    if (!st.isEmpty()) gear.add(Pair.of(s, st.copy()));
                }
                if (!gear.isEmpty()) out.add(new ClientboundSetEquipmentPacket(e.getId(), gear));
            }
            if (!e.getPassengers().isEmpty()) riders.add(e);
        }
        for (Entity e : riders) out.add(new ClientboundSetPassengersPacket(e));
        // What a server says once a joining player's first chunks are sent:
        // the client's "Loading terrain" waits for it (1.20.3 and later).
        Packet<?> start = RecWorld.loadStart();
        if (start != null) out.add(start);
    }
}
