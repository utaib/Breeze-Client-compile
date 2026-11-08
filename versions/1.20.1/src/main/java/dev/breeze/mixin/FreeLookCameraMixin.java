package dev.breeze.mixin;

import dev.breeze.modules.FreeLook;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Free Look's camera angles. Camera.setup turns the camera to where the
 * entity looks, then (in third person) moves it back along that direction:
 * this hands those turns Free Look's angles while looking, so the camera
 * both points and orbits with them.
 *
 * Every setRotation in setup, not only the first: from 1.21.2 the first one
 * is in a branch for riding the new minecarts (2.18.0's first run hooked
 * only that one, and the camera never moved on 1.21.2 and later). The turn
 * to the front view (yaw + 180) is left alone; FreeLook tells it apart.
 * ModifyArgs rather than a redirect, so another mod changing the same calls
 * still can; optional, so a Minecraft where the call differs runs without
 * Free Look (the self-test reports it).
 */
@Mixin(Camera.class)
public class FreeLookCameraMixin {

    @ModifyArgs(method = "setup", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V"))
    private void breeze$freeLook(Args args) {
        float yRot = args.get(0);
        float xRot = args.get(1);
        if (!FreeLook.takesCameraTurn(yRot, xRot)) return;
        args.set(0, FreeLook.yaw());
        args.set(1, FreeLook.pitch());
    }
}
