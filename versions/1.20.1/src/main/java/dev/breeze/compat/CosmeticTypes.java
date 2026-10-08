package dev.breeze.compat;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * The render types a 3D cosmetic draws with: entity cutout without face
 * culling (Blockbench models are often single-sided planes), and entity
 * translucent for blended materials. Where they are declared moved (1.21.11,
 * RenderTypes) and the no-cull cutout went (26.1), so versions/ has a copy per
 * form; this is the form up to 1.21.10.
 */
public final class CosmeticTypes {

    private CosmeticTypes() {}

    public static RenderType cutout(ResourceLocation texture) {
        return RenderType.entityCutoutNoCull(texture);
    }

    public static RenderType translucent(ResourceLocation texture) {
        return RenderType.entityTranslucent(texture);
    }
}
