package dev.breeze.mixin;

import dev.breeze.cosmetics.ShieldHolders;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 1.21.4 to 1.21.8: every item render state is filled here, with the stack
// and its owner. A shield in a player's hand is tagged with that player (any
// other item, or a reused state, with nobody), for ShieldStateMixin.
@Mixin(ItemModelResolver.class)
public class ShieldResolverMixin {

    @Inject(method = "appendItemLayers(Lnet/minecraft/client/renderer/item/ItemStackRenderState;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;I)V",
            at = @At("HEAD"))
    private void breeze$tag(ItemStackRenderState state, ItemStack stack, ItemDisplayContext context, Level level,
                            LivingEntity owner, int seed, CallbackInfo ci) {
        ((ShieldHolders.Tagged) state).breeze$tagShield(ShieldHolders.holderOf(owner, stack), context.name());
    }
}
