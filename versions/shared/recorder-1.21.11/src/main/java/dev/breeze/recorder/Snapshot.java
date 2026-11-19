package dev.breeze.recorder;

import com.mojang.datafixers.util.Pair;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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

        int cx = p.chunkPosition().x, cz = p.chunkPosition().z;
        out.add(new ClientboundSetChunkCacheCenterPacket(cx, cz));
        out.add(new ClientboundSetTimePacket(level.getGameTime(), level.getDayTime(), true));
        if (level.isRaining()) {
            out.add(new ClientboundGameEventPacket(ClientboundGameEventPacket.START_RAINING, 0));
            out.add(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, level.getRainLevel(1f)));
            out.add(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, level.getThunderLevel(1f)));
        }
        if (mc.gameMode != null) {
            out.add(new ClientboundGameEventPacket(ClientboundGameEventPacket.CHANGE_GAME_MODE, mc.gameMode.getPlayerMode().getId()));
        }

        // Chunks: every one the client holds within its view distance.
        int r = mc.options.getEffectiveRenderDistance() + 1;
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                LevelChunk c = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (c != null) out.add(new ClientboundLevelChunkWithLightPacket(c, level.getLightEngine(), null, null));
            }
        }

        out.add(ClientboundPlayerPositionPacket.of(0, PositionMoveRotation.of(p), Set.of()));
        out.add(new ClientboundSetHealthPacket(p.getHealth(), p.getFoodData().getFoodLevel(), p.getFoodData().getSaturationLevel()));
        out.add(new ClientboundSetExperiencePacket(p.experienceProgress, p.totalExperience, p.experienceLevel));
        out.add(new ClientboundContainerSetContentPacket(0, p.inventoryMenu.getStateId(), p.inventoryMenu.getItems(),
                p.inventoryMenu.getCarried()));
        out.add(new ClientboundSetHeldSlotPacket(p.getInventory().getSelectedSlot()));
        List<SynchedEntityData.DataValue<?>> own = p.getEntityData().getNonDefaultValues();
        if (own != null) out.add(new ClientboundSetEntityDataPacket(p.getId(), own));

        List<Entity> riders = new ArrayList<>();
        for (Entity e : level.entitiesForRendering()) {
            if (e == p || e instanceof Player) continue;
            out.add(new ClientboundAddEntityPacket(e.getId(), e.getUUID(), e.getX(), e.getY(), e.getZ(), e.getXRot(),
                    e.getYRot(), e.getType(), 0, e.getDeltaMovement(), e.getYHeadRot()));
            List<SynchedEntityData.DataValue<?>> data = e.getEntityData().getNonDefaultValues();
            if (data != null) out.add(new ClientboundSetEntityDataPacket(e.getId(), data));
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
        // the client's "Loading terrain" waits for it.
        out.add(new ClientboundGameEventPacket(ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START, 0));
        return out;
    }
}
