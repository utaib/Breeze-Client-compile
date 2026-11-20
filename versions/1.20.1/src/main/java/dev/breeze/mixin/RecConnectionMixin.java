package dev.breeze.mixin;

import dev.breeze.recorder.RecorderHooks;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every packet a connection receives, before Minecraft handles it, for the
 * recorder (RecorderHooks decides whether it is the client's and whether a
 * recording runs; when none does this costs one check). The method has kept
 * this shape from 1.17 to 26.3.
 */
@Mixin(Connection.class)
public class RecConnectionMixin {

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void breeze$record(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        RecorderHooks.onPacket((Connection) (Object) this, packet);
    }
}
