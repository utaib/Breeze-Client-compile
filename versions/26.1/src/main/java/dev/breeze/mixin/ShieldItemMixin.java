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
@Mixin(ShieldSpecialRenderer.class)
public class ShieldItemMixin {

    @Inject(method = "submit(Lnet/minecraft/core/component/DataComponentMap;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IIZI)V",
            at = @At("HEAD"), cancellable = true)
    private void breeze$shield(DataComponentMap components, PoseStack poseStack,
                               SubmitNodeCollector nodes, int light, int overlay, boolean foil, int outline, CallbackInfo ci) {
        ResourceLocation texture = ShieldDraw.textureFor(Minecraft.getInstance(), ShieldHolders.current(), ShieldHolders.currentContext());
        if (texture == null) return;
        ShieldDraw.draw(poseStack, (ps, tex, translucent, geo) -> nodes.submitCustomGeometry(ps, CosmeticTypes.cutout(tex),
                (pose, vc) -> geo.write(vc, pose)), texture, light, overlay, ShieldHolders.currentContext());
        ci.cancel();
    }
}
