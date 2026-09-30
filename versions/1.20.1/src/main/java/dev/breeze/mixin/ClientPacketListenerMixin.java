package dev.breeze.mixin;

import dev.breeze.TpsTracker;
import dev.breeze.modules.TimeChanger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {

    @Inject(method = "handleSetTime", at = @At("RETURN"))
    private void breeze$onTime(ClientboundSetTimePacket packet, CallbackInfo ci) {
        TpsTracker.onTimeUpdate();
        if (TimeChanger.active()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) mc.level.setDayTime(TimeChanger.time());
        }
    }
}
