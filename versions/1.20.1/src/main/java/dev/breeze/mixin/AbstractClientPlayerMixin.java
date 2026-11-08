package dev.breeze.mixin;

import dev.breeze.modules.FovChanges;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractClientPlayer.class)
public class AbstractClientPlayerMixin {

    @Inject(method = "getFieldOfViewModifier", at = @At("RETURN"), cancellable = true)
    private void breeze$fov(CallbackInfoReturnable<Float> cir) {
        if (FovChanges.active()) cir.setReturnValue(1.0f);
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "isCapeLoaded", at = @org.spongepowered.asm.mixin.injection.At("RETURN"), cancellable = true)
    private void breeze$capeLoaded(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        net.minecraft.resources.ResourceLocation cape = dev.breeze.cape.RemoteCapes.capeFor(((net.minecraft.client.player.AbstractClientPlayer) (Object) this).getUUID());
        if (cape != null) {
            cir.setReturnValue(true);
        }
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "getCloakTextureLocation", at = @org.spongepowered.asm.mixin.injection.At("RETURN"), cancellable = true)
    private void breeze$capeTexture(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<net.minecraft.resources.ResourceLocation> cir) {
        net.minecraft.resources.ResourceLocation cape = dev.breeze.cape.RemoteCapes.capeFor(((net.minecraft.client.player.AbstractClientPlayer) (Object) this).getUUID());
        if (cape != null) {
            cir.setReturnValue(cape);
        }
    }
}
