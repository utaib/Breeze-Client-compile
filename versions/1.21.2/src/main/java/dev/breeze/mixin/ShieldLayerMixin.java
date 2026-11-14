package dev.breeze.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.breeze.cosmetics.ShieldHolders;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 1.21.2 and 1.21.3: the hand layer draws a player's items from the render
// state, whose stacks need not be the entity's own, so the shield renderer
// may not find the holder by the stack. The player is set around the layer.
@Mixin(ItemInHandLayer.class)
public class ShieldLayerMixin {

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;FF)V", at = @At("HEAD"))
    private void breeze$holder(PoseStack poseStack, MultiBufferSource buffer, int light, LivingEntityRenderState state,
                               float yRot, float xRot, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        Entity e = state instanceof PlayerRenderState p && mc.level != null ? mc.level.getEntity(p.id) : null;
        ShieldHolders.enter(e == null ? null : e.getUUID(), null);
    }

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;FF)V", at = @At("RETURN"))
    private void breeze$done(PoseStack poseStack, MultiBufferSource buffer, int light, LivingEntityRenderState state,
                             float yRot, float xRot, CallbackInfo ci) {
        ShieldHolders.exit();
    }
}
