package dev.breeze.compat;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * Images Breeze makes itself (capes decoded from the network) and hands to the
 * GPU. Both calls changed shape in later Minecraft versions, so they live here
 * (versions/ has a copy per change). This is the 1.21.2 form.
 */
public final class Images {

    private Images() {}

    /** One pixel, given as ARGB (as Java's BufferedImage.getRGB returns it). */
    public static void setPixelArgb(NativeImage img, int x, int y, int argb) {
        img.setPixel(x, y, argb);
    }

    /** Uploads img as a texture under id, replacing (and freeing) any texture already there. */
    public static void register(ResourceLocation id, NativeImage img) {
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(img));
    }
}
