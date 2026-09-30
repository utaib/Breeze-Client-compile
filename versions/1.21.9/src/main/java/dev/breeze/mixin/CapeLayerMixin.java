package dev.breeze.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.breeze.modules.CustomCape;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 1.21.9 and later: the layer submits the cape from the player's (avatar's)
// render state; the pose pushed here still applies to what is submitted.
@Mixin(CapeLayer.class)
public class CapeLayerMixin {

    @Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V", at = @At("HEAD"))
    private void breeze$wavePush(PoseStack poseStack, SubmitNodeCollector nodes, int packedLight, AvatarRenderState state, float yRot, float xRot, CallbackInfo ci) {
        dev.breeze.devtest.AutoTest.capeLayer();
        if (!CustomCape.active()) return;
        poseStack.pushPose();
        float wave = ((float) Math.sin(state.ageInTicks * 0.18f) * 0.5f + 0.5f) * (2.0f + state.walkAnimationSpeed * 5.0f);
        poseStack.translate(0.0, 0.0, 0.125);
        poseStack.mulPose(Axis.XP.rotationDegrees(wave));
        poseStack.translate(0.0, 0.0, -0.125);
    }

    @Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V", at = @At("RETURN"))
    private void breeze$wavePop(PoseStack poseStack, SubmitNodeCollector nodes, int packedLight, AvatarRenderState state, float yRot, float xRot, CallbackInfo ci) {
        if (CustomCape.active()) poseStack.popPose();
    }
}
