package dev.breeze.mixin;

import dev.breeze.modules.FreeLook;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The mouse turns the local player through Entity.turn; while Free Look is
 * on, the turn goes to the camera instead and the player keeps facing where
 * they were. Optional, like FreeLookCameraMixin.
 */
@Mixin(Entity.class)
public class FreeLookTurnMixin {

    @Inject(method = "turn", at = @At("HEAD"), cancellable = true, require = 0)
    private void breeze$freeLook(double yRot, double xRot, CallbackInfo ci) {
        if (FreeLook.turn(this, yRot, xRot)) ci.cancel();
    }
}
