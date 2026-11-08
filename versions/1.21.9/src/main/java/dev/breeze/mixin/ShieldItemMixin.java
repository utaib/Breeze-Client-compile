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
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 1.21.9 to 1.21.11: as from 1.21.4, but the shield is submitted for drawing
// later, so the custom one is submitted the same way (as the 3D cosmetics are).
@Mixin(ShieldSpecialRenderer.class)
public class ShieldItemMixin {

    @Inject(method = "submit(Lnet/minecraft/core/component/DataComponentMap;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IIZI)V",
            at = @At("HEAD"), cancellable = true)
    private void breeze$shield(DataComponentMap components, ItemDisplayContext context, PoseStack poseStack,
                               SubmitNodeCollector nodes, int light, int overlay, boolean foil, int outline, CallbackInfo ci) {
        ResourceLocation texture = ShieldDraw.textureFor(Minecraft.getInstance(), ShieldHolders.current(), context.name());
        if (texture == null) return;
        ShieldDraw.draw(poseStack, (ps, tex, translucent, geo) -> nodes.submitCustomGeometry(ps, CosmeticTypes.cutout(tex),
                (pose, vc) -> geo.write(vc, pose)), texture, light, overlay, context.name());
        ci.cancel();
    }
}
