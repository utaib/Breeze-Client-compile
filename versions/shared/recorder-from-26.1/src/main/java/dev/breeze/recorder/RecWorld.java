package dev.breeze.recorder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.List;
import java.util.Set;

/**
 * The packets a server would send to describe the world as the client
 * has it (Minecraft 26.1 and later); Snapshot puts them together.
 */
final class RecWorld {

    private RecWorld() {}

    static int centerX(LocalPlayer p) {
        return p.chunkPosition().x();
    }

    static int centerZ(LocalPlayer p) {
        return p.chunkPosition().z();
    }

    static int renderDistance(Minecraft mc) {
        return mc.options.getEffectiveRenderDistance();
    }

    static LevelChunk chunk(ClientLevel level, int x, int z) {
        return level.getChunkSource().getChunk(x, z, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
    }

    static void chunkPackets(List<Packet<?>> out, LevelChunk c, ClientLevel level) {
        out.add(new ClientboundLevelChunkWithLightPacket(c, level.getLightEngine(), null, null));
    }

    static Packet<?> time(ClientLevel level) {
        return new ClientboundSetTimePacket(level.getGameTime(), java.util.Map.of());
    }

    static Packet<?> position(LocalPlayer p) {
        return ClientboundPlayerPositionPacket.of(0, net.minecraft.world.entity.PositionMoveRotation.of(p), Set.of());
    }

    static Packet<?> inventory(LocalPlayer p) {
        return new ClientboundContainerSetContentPacket(0, p.inventoryMenu.getStateId(), p.inventoryMenu.getItems(), p.inventoryMenu.getCarried());
    }

    static Packet<?> heldSlot(int slot) {
        return new ClientboundSetHeldSlotPacket(slot);
    }

    /** The entity's data that differs from the default, or null. */
    static Packet<?> entityData(Entity e) {
        List<SynchedEntityData.DataValue<?>> data = e.getEntityData().getNonDefaultValues();
        return data == null ? null : new ClientboundSetEntityDataPacket(e.getId(), data);
    }

    static void addEntity(List<Packet<?>> out, Entity e) {
        out.add(new ClientboundAddEntityPacket(e.getId(), e.getUUID(), e.getX(), e.getY(), e.getZ(), e.getXRot(),
                e.getYRot(), e.getType(), 0, e.getDeltaMovement(), e.getYHeadRot()));
    }

    /** What a server sends once a joining player's first chunks are on their way, or null before 1.20.3. */
    static Packet<?> loadStart() {
        return new ClientboundGameEventPacket(ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START, 0);
    }
}
