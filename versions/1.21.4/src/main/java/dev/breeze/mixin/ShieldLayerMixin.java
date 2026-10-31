package dev.breeze.mixin;

import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import org.spongepowered.asm.mixin.Mixin;

// Custom Shield from 1.21.4: the item render state carries its holder
// (ShieldResolverMixin, ShieldStateMixin), so the hand layer needs nothing.
// Empty, replacing versions/1.21.2's; the mixin list names it everywhere.
@Mixin(ItemInHandLayer.class)
public class ShieldLayerMixin {
}
