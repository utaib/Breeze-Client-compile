package dev.breeze.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.breeze.cosmetics.ShieldHolders;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

// From 1.21.9, items are submitted rather than drawn at once: an item render
// state remembers who held its item (ShieldResolverMixin) and says so while
// it is submitted, for ShieldItemMixin, which runs inside that call.
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

    @Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V", at = @At("HEAD"))
    private void breeze$enter(PoseStack poseStack, SubmitNodeCollector nodes, int light, int overlay, int outline, CallbackInfo ci) {
        ShieldHolders.enter(breeze$holder, breeze$context);
    }

    @Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V", at = @At("RETURN"))
    private void breeze$exit(PoseStack poseStack, SubmitNodeCollector nodes, int light, int overlay, int outline, CallbackInfo ci) {
        ShieldHolders.exit();
    }
}
