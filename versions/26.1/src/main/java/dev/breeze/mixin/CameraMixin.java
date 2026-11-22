package dev.breeze.mixin;

import dev.breeze.modules.Zoom;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Zoom from 26.1, where the camera holds the field of view (GameRenderer's
 * getFov is gone). UNVERIFIED in a world: that the projection is built from
 * getFov() rather than from the field behind it.
 */
@Mixin(Camera.class)
public class CameraMixin {

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void breeze$zoom(CallbackInfoReturnable<Float> cir) {
        if (Zoom.active()) cir.setReturnValue(cir.getReturnValueF() * 0.30f);
    }
}
