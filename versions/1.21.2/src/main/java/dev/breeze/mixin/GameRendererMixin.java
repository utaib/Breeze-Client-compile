package dev.breeze.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.breeze.modules.NoHurtCam;
import dev.breeze.modules.Zoom;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * From 1.21.2, GameRenderer.getFov returns a float, not a double. Reading it
 * as a double (the form up to 1.21.1) threw ClassCastException on the first
 * frame after Zoom was switched on and crashed the game (found by the
 * self-test's module sweep on 1.21.9).
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void breeze$zoom(Camera camera, float partialTick, boolean useFovSetting, CallbackInfoReturnable<Float> cir) {
        if (Zoom.active()) cir.setReturnValue(cir.getReturnValueF() * 0.30f);
    }

    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    private void breeze$noHurtCam(PoseStack poseStack, float partialTick, CallbackInfo ci) {
        if (NoHurtCam.active()) ci.cancel();
    }
}
