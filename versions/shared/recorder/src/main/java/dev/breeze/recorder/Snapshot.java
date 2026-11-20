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
 * The world as the client has it at the moment recording starts, as the
 * packets a server would send a player joining it now: where the player is,
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

    private Snapshot() {}

    static List<Packet<?>> of(Minecraft mc) {
        List<Packet<?>> out = new ArrayList<>();
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (p == null || level == null) return out;

        int cx = RecWorld.centerX(p), cz = RecWorld.centerZ(p);
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

        // Chunks: every one the client holds within its view distance.
        int r = RecWorld.renderDistance(mc) + 1;
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                LevelChunk c = RecWorld.chunk(level, x, z);
                if (c != null) RecWorld.chunkPackets(out, c, level);
            }
        }

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
        return out;
    }
}
