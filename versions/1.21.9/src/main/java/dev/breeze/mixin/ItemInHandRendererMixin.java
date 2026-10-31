package dev.breeze.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.breeze.modules.ItemScale;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 1.21.9 and later: items are submitted to a node collector; the pose
// transform still applies to what is submitted.
@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererMixin {

    @Inject(method = "renderItem", at = @At("HEAD"))
    private void breeze$scalePush(LivingEntity entity, ItemStack stack, ItemDisplayContext ctx, PoseStack poseStack, SubmitNodeCollector nodes, int seed, CallbackInfo ci) {
        if (ItemScale.active()) {
            poseStack.pushPose();
            float s = ItemScale.scale();
            poseStack.scale(s, s, s);
        }
    }

    @Inject(method = "renderItem", at = @At("RETURN"))
    private void breeze$scalePop(LivingEntity entity, ItemStack stack, ItemDisplayContext ctx, PoseStack poseStack, SubmitNodeCollector nodes, int seed, CallbackInfo ci) {
        if (ItemScale.active()) {
            poseStack.popPose();
        }
    }
}
