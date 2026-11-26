package dev.breeze.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.breeze.compat.CosmeticTypes;
import dev.breeze.cosmetics.ShieldDraw;
import dev.breeze.cosmetics.ShieldHolders;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.special.ShieldSpecialRenderer;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 26.x: as 1.21.9 (the shield is submitted for drawing later, so the custom
// one is submitted the same way, as the 3D cosmetics are), without the
// display context, which the item render state keeps (ShieldStateMixin).
//
// Up to 1.21.11 submit() turned the pose itself (scale 1, -1, -1), which
// ShieldMesh has built in. From 26.1 that turn is the renderer's
// DEFAULT_TRANSFORMATION, multiplied into the pose by the item layer before
// submit() is called (LayerRenderState.applyTransform, read from 26.1 and
// 26.3's bytecode, API dump run 41203844732). Drawn as before, the shield
// was turned twice and showed its back (2.23.0's screenshots), so the turn
// is undone here first.
@Mixin(ShieldSpecialRenderer.class)
public class ShieldItemMixin {

    @Inject(method = "submit(Lnet/minecraft/core/component/DataComponentMap;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IIZI)V",
            at = @At("HEAD"), cancellable = true)
    private void breeze$shield(DataComponentMap components, PoseStack poseStack,
                               SubmitNodeCollector nodes, int light, int overlay, boolean foil, int outline, CallbackInfo ci) {
        ResourceLocation texture = ShieldDraw.textureFor(Minecraft.getInstance(), ShieldHolders.current(), ShieldHolders.currentContext());
        if (texture == null) return;
        poseStack.pushPose();
        poseStack.scale(1.0F, -1.0F, -1.0F);
        try {
            ShieldDraw.draw(poseStack, (ps, tex, translucent, geo) -> nodes.submitCustomGeometry(ps, CosmeticTypes.cutout(tex),
                    (pose, vc) -> geo.write(vc, pose)), texture, light, overlay, ShieldHolders.currentContext());
        } finally {
            poseStack.popPose();
        }
        ci.cancel();
    }
}
