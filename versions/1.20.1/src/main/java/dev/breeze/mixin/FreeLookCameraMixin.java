package dev.breeze.mixin;

import dev.breeze.modules.FreeLook;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Free Look's camera angles. Camera.setup first turns the camera to where
 * the entity looks, then (in third person) moves it back along that
 * direction: these hand that first turn Free Look's angles while looking, so
 * the camera both points and orbits with them. ModifyArg rather than a
 * redirect, so another mod changing the same call still can; optional, so a
 * Minecraft where the call differs runs without Free Look (the self-test
 * reports it).
 */
@Mixin(Camera.class)
public class FreeLookCameraMixin {

    @ModifyArg(method = "setup", require = 0, index = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V", ordinal = 0))
    private float breeze$freeLookYaw(float yRot) {
        return FreeLook.cameraYaw(yRot);
    }

    @ModifyArg(method = "setup", require = 0, index = 1,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V", ordinal = 0))
    private float breeze$freeLookPitch(float xRot) {
        return FreeLook.cameraPitch(xRot);
    }
}
