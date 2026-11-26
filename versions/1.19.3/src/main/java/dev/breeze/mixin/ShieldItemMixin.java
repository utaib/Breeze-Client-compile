package dev.breeze.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.breeze.compat.CosmeticTypes;
import dev.breeze.cosmetics.ShieldDraw;
import dev.breeze.cosmetics.ShieldHolders;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

// 1.17 to 1.19.3: as from 1.19.4 (versions/1.20.1), with
// ItemTransforms.TransformType for how the item is shown; its names (GUI,
// FIRST_PERSON_LEFT_HAND and so on) are the ones ItemDisplayContext kept.
@Mixin(BlockEntityWithoutLevelRenderer.class)
public class ShieldItemMixin {

    @Inject(method = "renderByItem", at = @At("HEAD"), cancellable = true)
    private void breeze$shield(ItemStack stack, ItemTransforms.TransformType context, PoseStack poseStack, MultiBufferSource buffer,
                               int light, int overlay, CallbackInfo ci) {
        if (stack.getItem() != Items.SHIELD) return;
        Minecraft mc = Minecraft.getInstance();
        UUID holder = ShieldHolders.current();
        if (holder == null) holder = ShieldHolders.holding(mc, stack);
        ResourceLocation texture = ShieldDraw.textureFor(mc, holder, context.name());
        if (texture == null) return;
        ShieldDraw.draw(poseStack, (ps, tex, translucent, geo) -> geo.write(buffer.getBuffer(CosmeticTypes.cutout(tex)), ps.last()),
                texture, light, overlay, context.name());
        ci.cancel();
    }
}
