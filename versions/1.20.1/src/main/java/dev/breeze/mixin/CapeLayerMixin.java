package dev.breeze.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.breeze.modules.CustomCape;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CapeLayer.class)
public class CapeLayerMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void breeze$wavePush(PoseStack poseStack, MultiBufferSource buffer, int packedLight, AbstractClientPlayer player, float limbSwing, float limbSwingAmount, float partialTicks, float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo ci) {
        dev.breeze.devtest.AutoTest.capeLayer();
        if (!CustomCape.active()) return;
        poseStack.pushPose();
        float wave = ((float) Math.sin(ageInTicks * 0.18f) * 0.5f + 0.5f) * (2.0f + limbSwingAmount * 5.0f);
        poseStack.translate(0.0, 0.0, 0.125);
        poseStack.mulPose(Axis.XP.rotationDegrees(wave));
        poseStack.translate(0.0, 0.0, -0.125);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void breeze$wavePop(PoseStack poseStack, MultiBufferSource buffer, int packedLight, AbstractClientPlayer player, float limbSwing, float limbSwingAmount, float partialTicks, float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo ci) {
        if (CustomCape.active()) poseStack.popPose();
    }
}
