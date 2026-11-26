package dev.breeze.recmixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.UUID;

/** The connection's player list, so a watched recording can give the player their own skin (RecSkin). */
@Mixin(ClientPacketListener.class)
public interface PlayerInfoMapAccess {

    @Accessor("playerInfoMap")
    Map<UUID, PlayerInfo> breeze$playerInfoMap();
}
