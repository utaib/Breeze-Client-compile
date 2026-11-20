package dev.breeze.recmixin;

import com.mojang.blaze3d.platform.NativeImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Where a picture's pixels are in memory (Minecraft before 1.21.5 keeps the address private). */
@Mixin(NativeImage.class)
public interface NativeImageAccess {

    @Accessor("pixels")
    long breeze$pixels();
}
