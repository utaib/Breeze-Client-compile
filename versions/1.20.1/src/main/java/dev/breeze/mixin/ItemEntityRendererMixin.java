package dev.breeze.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.breeze.modules.ItemPhysics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntityRenderer.class)
public class ItemEntityRendererMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void breeze$flatPush(ItemEntity entity, float yaw, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight, CallbackInfo ci) {
        if (ItemPhysics.active()) {
            poseStack.pushPose();
            poseStack.translate(0.0, -0.18, 0.0);
            poseStack.mulPose(Axis.XP.rotationDegrees(90.0f));
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void breeze$flatPop(ItemEntity entity, float yaw, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight, CallbackInfo ci) {
        if (ItemPhysics.active()) {
            poseStack.popPose();
        }
    }
}
