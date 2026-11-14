package dev.breeze.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.breeze.cosmetics.ShieldHolders;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

// 1.21.4 to 1.21.8: an item render state remembers who held its item
// (ShieldResolverMixin), and says so while it is drawn, for ShieldItemMixin.
@Mixin(ItemStackRenderState.class)
public class ShieldStateMixin implements ShieldHolders.Tagged {

    @Unique
    private UUID breeze$holder;
    @Unique
    private String breeze$context;

    @Override
    public void breeze$tagShield(UUID holder, String context) {
        this.breeze$holder = holder;
        this.breeze$context = context;
    }

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V", at = @At("HEAD"))
    private void breeze$enter(PoseStack poseStack, MultiBufferSource buffer, int light, int overlay, CallbackInfo ci) {
        ShieldHolders.enter(breeze$holder, breeze$context);
    }

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V", at = @At("RETURN"))
    private void breeze$exit(PoseStack poseStack, MultiBufferSource buffer, int light, int overlay, CallbackInfo ci) {
        ShieldHolders.exit();
    }
}
