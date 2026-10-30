package dev.breeze.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.breeze.modules.CustomCape;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 1.21.2 to 1.21.8: the layer draws from the player's render state; its age
// and walk speed drive the wave as the entity's did before.
@Mixin(CapeLayer.class)
public class CapeLayerMixin {

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/renderer/entity/state/PlayerRenderState;FF)V", at = @At("HEAD"))
    private void breeze$wavePush(PoseStack poseStack, MultiBufferSource buffer, int packedLight, PlayerRenderState state, float yRot, float xRot, CallbackInfo ci) {
        dev.breeze.devtest.AutoTest.capeLayer();
        net.minecraft.client.model.HumanoidModel<?> model = breeze$model();
        dev.breeze.cosmetics.CosmeticRender.draw(poseStack,
                (ps, tex, translucent, geo) -> geo.write(buffer.getBuffer(translucent
                        ? dev.breeze.compat.CosmeticTypes.translucent(tex) : dev.breeze.compat.CosmeticTypes.cutout(tex)), ps.last()),
                packedLight, breeze$uuid(state.id), model.head, model.body, model.rightArm, state.ageInTicks, state.walkAnimationSpeed, state.isInvisible);
        if (!CustomCape.active()) return;
        poseStack.pushPose();
        float wave = ((float) Math.sin(state.ageInTicks * 0.18f) * 0.5f + 0.5f) * (2.0f + state.walkAnimationSpeed * 5.0f);
        poseStack.translate(0.0, 0.0, 0.125);
        poseStack.mulPose(Axis.XP.rotationDegrees(wave));
        poseStack.translate(0.0, 0.0, -0.125);
    }

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/renderer/entity/state/PlayerRenderState;FF)V", at = @At("RETURN"))
    private void breeze$wavePop(PoseStack poseStack, MultiBufferSource buffer, int packedLight, PlayerRenderState state, float yRot, float xRot, CallbackInfo ci) {
        if (CustomCape.active()) poseStack.popPose();
    }

    /** The player model this layer draws on: its head, body and right arm carry cosmetics. */
    @org.spongepowered.asm.mixin.Unique
    private net.minecraft.client.model.HumanoidModel<?> breeze$model() {
        return (net.minecraft.client.model.HumanoidModel<?>) ((net.minecraft.client.renderer.entity.layers.RenderLayer<?, ?>) (Object) this).getParentModel();
    }

    @org.spongepowered.asm.mixin.Unique
    private static java.util.UUID breeze$uuid(int entityId) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        net.minecraft.world.entity.Entity e = mc.level == null ? null : mc.level.getEntity(entityId);
        return e == null ? null : e.getUUID();
    }
}
