package dev.breeze.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.breeze.modules.ItemScale;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Item Scale from 26.3, where ItemInHandRenderer became
 * FirstPersonHandsAndItemsRenderer and has no method of its own for the held
 * item. The scale is pushed just before submitArmWithItem submits the item
 * (ItemStackRenderState.submit, called there twice, per the API dump's call
 * list) and popped just after, so the arm keeps its size.
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public class ItemInHandRendererMixin {

    private static final String SUBMIT_ITEM =
            "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V";

    @Inject(method = "submitArmWithItem", at = @At(value = "INVOKE", target = SUBMIT_ITEM))
    private void breeze$scalePush(PlayerRenderState player, FirstPersonHandsAndItemsRenderState hands, float a, float b,
                                  InteractionHand hand, float c, ItemStack stack, float d, PoseStack poseStack,
                                  SubmitNodeCollector nodes, int light, CallbackInfo ci) {
        if (ItemScale.active()) {
            poseStack.pushPose();
            float s = ItemScale.scale();
            poseStack.scale(s, s, s);
        }
    }

    @Inject(method = "submitArmWithItem", at = @At(value = "INVOKE", target = SUBMIT_ITEM, shift = At.Shift.AFTER))
    private void breeze$scalePop(PlayerRenderState player, FirstPersonHandsAndItemsRenderState hands, float a, float b,
                                 InteractionHand hand, float c, ItemStack stack, float d, PoseStack poseStack,
                                 SubmitNodeCollector nodes, int light, CallbackInfo ci) {
        if (ItemScale.active()) {
            poseStack.popPose();
        }
    }
}
