package dev.breeze.mixin;

import dev.breeze.modules.FreeLook;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Free Look's camera angles on 26.x. Camera.setup is gone: Camera.update
 * calls alignWithEntity(float), which holds the same four setRotation(FF)
 * calls (read from 26.1's and 26.3's bytecode by the self-test's
 * camera-shape line, run 41065758284). Otherwise as versions/1.20.1: every
 * call, the front view left alone, optional.
 */
@Mixin(Camera.class)
public class FreeLookCameraMixin {

    @ModifyArgs(method = "alignWithEntity", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V"))
    private void breeze$freeLook(Args args) {
        float yRot = args.get(0);
        float xRot = args.get(1);
        if (!FreeLook.takesCameraTurn(yRot, xRot)) return;
        args.set(0, FreeLook.yaw());
        args.set(1, FreeLook.pitch());
    }
}
