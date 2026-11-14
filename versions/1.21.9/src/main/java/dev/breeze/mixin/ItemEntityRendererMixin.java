package dev.breeze.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.breeze.modules.ItemPhysics;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 1.21.9 and later: items are submitted from an ItemEntityRenderState.
@Mixin(ItemEntityRenderer.class)
public class ItemEntityRendererMixin {

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V", at = @At("HEAD"))
    private void breeze$flatPush(ItemEntityRenderState state, PoseStack poseStack, SubmitNodeCollector nodes, CameraRenderState camera, CallbackInfo ci) {
        if (ItemPhysics.active()) {
            poseStack.pushPose();
            poseStack.translate(0.0, -0.18, 0.0);
            poseStack.mulPose(Axis.XP.rotationDegrees(90.0f));
        }
    }

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V", at = @At("RETURN"))
    private void breeze$flatPop(ItemEntityRenderState state, PoseStack poseStack, SubmitNodeCollector nodes, CameraRenderState camera, CallbackInfo ci) {
        if (ItemPhysics.active()) {
            poseStack.popPose();
        }
    }
}
