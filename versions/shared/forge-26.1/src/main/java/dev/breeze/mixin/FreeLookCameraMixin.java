package dev.breeze.mixin;

import dev.breeze.modules.FreeLook;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Free Look's camera angles on Forge 26.x: as shared/forge (two @ModifyArg,
 * since Forge cannot load @ModifyArgs' generated classes), on 26.x's
 * Camera.alignWithEntity, which holds the setRotation calls
 * (versions/26.1/.../FreeLookCameraMixin).
 */
@Mixin(Camera.class)
public class FreeLookCameraMixin {

    @Unique
    private boolean breeze$halfway;
    @Unique
    private boolean breeze$take;

    @Unique
    private boolean breeze$decide(float yRot, float xRot) {
        if (breeze$halfway) {
            breeze$halfway = false;
            return breeze$take;
        }
        breeze$halfway = true;
        breeze$take = FreeLook.takesCameraTurn(yRot, xRot);
        return breeze$take;
    }

    @ModifyArg(method = "alignWithEntity", require = 0, index = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V"))
    private float breeze$freeLookYaw(float yRot, float xRot) {
        return breeze$decide(yRot, xRot) ? FreeLook.yaw() : yRot;
    }

    @ModifyArg(method = "alignWithEntity", require = 0, index = 1,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V"))
    private float breeze$freeLookPitch(float yRot, float xRot) {
        return breeze$decide(yRot, xRot) ? FreeLook.pitch() : xRot;
    }
}
