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

@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void breeze$zoom(Camera camera, float partialTick, boolean useFovSetting, CallbackInfoReturnable<Double> cir) {
        if (Zoom.active()) cir.setReturnValue(cir.getReturnValueD() * 0.30);
    }

    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    private void breeze$noHurtCam(PoseStack poseStack, float partialTick, CallbackInfo ci) {
        if (NoHurtCam.active()) ci.cancel();
    }
}
