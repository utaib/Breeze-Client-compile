package dev.breeze.mixin;

import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import org.spongepowered.asm.mixin.Mixin;

// Custom Shield: nothing to do here up to 1.21.1 (the hand layer draws the
// entity's own stacks, which ShieldHolders finds) or from 1.21.4 (render
// states are tagged instead). The one that does something is in
// versions/1.21.2; the shared mixin list names this class for every version.
@Mixin(ItemInHandLayer.class)
public class ShieldLayerMixin {
}
