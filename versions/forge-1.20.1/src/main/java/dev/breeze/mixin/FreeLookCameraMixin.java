package dev.breeze.mixin;

import dev.breeze.modules.FreeLook;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Free Look's camera angles on Forge 1.20.1: the Fabric build's hook
 * (versions/1.20.1) with two ModifyArg in place of one ModifyArgs. Forge
 * 1.20.1 cannot load the class Mixin generates for ModifyArgs
 * (NoClassDefFoundError org/spongepowered/asm/synthetic/args/Args$1, live
 * loader test run 43617930566, the game stopped while loading); NeoForge and
 * Fabric can, and keep the original.
 *
 * Each handler sees both angles of the call. Free Look counts the turns it
 * takes, so the call is decided once: whichever handler runs first decides
 * from the angles as Minecraft passed them, the other uses that answer.
 * Otherwise as versions/1.20.1: every setRotation in setup, the front view
 * left alone, optional.
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

    @ModifyArg(method = "setup", require = 0, index = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V"))
    private float breeze$freeLookYaw(float yRot, float xRot) {
        return breeze$decide(yRot, xRot) ? FreeLook.yaw() : yRot;
    }

    @ModifyArg(method = "setup", require = 0, index = 1,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V"))
    private float breeze$freeLookPitch(float yRot, float xRot) {
        return breeze$decide(yRot, xRot) ? FreeLook.pitch() : xRot;
    }
}
