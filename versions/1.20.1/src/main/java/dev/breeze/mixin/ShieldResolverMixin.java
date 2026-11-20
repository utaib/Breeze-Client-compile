package dev.breeze.mixin;

import net.minecraft.client.renderer.entity.ItemRenderer;
import org.spongepowered.asm.mixin.Mixin;

// Custom Shield: only from 1.21.4, where items are drawn from render states
// (versions/1.21.4 and versions/1.21.9). Empty here; the shared mixin list
// names this class for every version.
@Mixin(ItemRenderer.class)
public class ShieldResolverMixin {
}
