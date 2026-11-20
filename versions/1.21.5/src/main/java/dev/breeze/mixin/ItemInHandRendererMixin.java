package dev.breeze.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.breeze.modules.ItemScale;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 1.21.5 to 1.21.8: renderItem no longer takes the left-hand flag.
@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererMixin {

    @Inject(method = "renderItem", at = @At("HEAD"))
    private void breeze$scalePush(LivingEntity entity, ItemStack stack, ItemDisplayContext ctx, PoseStack poseStack, MultiBufferSource buffer, int seed, CallbackInfo ci) {
        if (ItemScale.active()) {
            poseStack.pushPose();
            float s = ItemScale.scale();
            poseStack.scale(s, s, s);
        }
    }

    @Inject(method = "renderItem", at = @At("RETURN"))
    private void breeze$scalePop(LivingEntity entity, ItemStack stack, ItemDisplayContext ctx, PoseStack poseStack, MultiBufferSource buffer, int seed, CallbackInfo ci) {
        if (ItemScale.active()) {
            poseStack.popPose();
        }
    }
}
