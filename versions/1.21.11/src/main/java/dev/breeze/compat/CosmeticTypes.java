package dev.breeze.compat;

import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.ResourceLocation;

/**
 * The render types a 3D cosmetic draws with. This is the 1.21.11 form: the
 * factories are on RenderTypes.
 */
public final class CosmeticTypes {

    private CosmeticTypes() {}

    public static RenderType cutout(ResourceLocation texture) {
        return RenderTypes.entityCutoutNoCull(texture);
    }

    public static RenderType translucent(ResourceLocation texture) {
        return RenderTypes.entityTranslucent(texture);
    }
}
