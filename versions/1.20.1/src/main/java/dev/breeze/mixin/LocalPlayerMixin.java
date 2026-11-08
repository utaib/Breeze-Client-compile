package dev.breeze.mixin;

import dev.breeze.modules.DropPrevention;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LocalPlayer.class)
public class LocalPlayerMixin {

    @Inject(method = "drop(Z)Z", at = @At("HEAD"), cancellable = true)
    private void breeze$drop(boolean fullStack, CallbackInfoReturnable<Boolean> cir) {
        if (DropPrevention.active()) {
            cir.setReturnValue(false);
        }
    }
}
