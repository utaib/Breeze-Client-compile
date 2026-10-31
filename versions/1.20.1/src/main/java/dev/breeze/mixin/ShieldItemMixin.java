package dev.breeze.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.breeze.compat.CosmeticTypes;
import dev.breeze.cosmetics.ShieldDraw;
import dev.breeze.cosmetics.ShieldHolders;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

// 1.19.4 to 1.21.3: the shield is drawn by the item renderer for built-in
// models, told the stack and how it is shown. A custom shield is drawn in its
// place, with the same pose (ShieldMesh is already turned as the game turns
// its shield), and the game's own drawing is skipped.
@Mixin(BlockEntityWithoutLevelRenderer.class)
public class ShieldItemMixin {

    @Inject(method = "renderByItem", at = @At("HEAD"), cancellable = true)
    private void breeze$shield(ItemStack stack, ItemDisplayContext context, PoseStack poseStack, MultiBufferSource buffer,
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
