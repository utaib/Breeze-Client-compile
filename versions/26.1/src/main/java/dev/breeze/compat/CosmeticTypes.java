package dev.breeze.compat;

import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.ResourceLocation;

/**
 * The render types a 3D cosmetic draws with. This is the 26.1 and later form:
 * entityCutout draws both faces (a culling variant is separate), and there is
 * no entityCutoutNoCull any more.
 */
public final class CosmeticTypes {

    private CosmeticTypes() {}

    public static RenderType cutout(ResourceLocation texture) {
        return RenderTypes.entityCutout(texture);
    }

    public static RenderType translucent(ResourceLocation texture) {
        return RenderTypes.entityTranslucent(texture);
    }
}
