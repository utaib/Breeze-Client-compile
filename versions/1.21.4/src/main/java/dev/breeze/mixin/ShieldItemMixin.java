package dev.breeze.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.breeze.compat.CosmeticTypes;
import dev.breeze.cosmetics.ShieldDraw;
import dev.breeze.cosmetics.ShieldHolders;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.special.ShieldSpecialRenderer;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 1.21.4 to 1.21.8: the shield has its own special renderer, told only the
// item's components; its holder comes from the item render state being drawn
// (ShieldStateMixin). Same pose as up to 1.21.3: the custom shield is drawn
// in the game's place and the game's own skipped.
@Mixin(ShieldSpecialRenderer.class)
public class ShieldItemMixin {

    @Inject(method = "render(Lnet/minecraft/core/component/DataComponentMap;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IIZ)V",
            at = @At("HEAD"), cancellable = true)
    private void breeze$shield(DataComponentMap components, ItemDisplayContext context, PoseStack poseStack,
                               MultiBufferSource buffer, int light, int overlay, boolean foil, CallbackInfo ci) {
        ResourceLocation texture = ShieldDraw.textureFor(Minecraft.getInstance(), ShieldHolders.current(), context.name());
        if (texture == null) return;
        ShieldDraw.draw(poseStack, (ps, tex, translucent, geo) -> geo.write(buffer.getBuffer(CosmeticTypes.cutout(tex)), ps.last()),
                texture, light, overlay, context.name());
        ci.cancel();
    }
}
