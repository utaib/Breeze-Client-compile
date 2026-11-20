package dev.breeze.recorder;

import com.mojang.blaze3d.platform.NativeImage;

/** Where a picture's pixels are in memory (Minecraft 1.17 to 1.21.4). */
final class RecPixels {

    private RecPixels() {}

    static long pointer(NativeImage img) {
        return ((dev.breeze.recmixin.NativeImageAccess) (Object) img).breeze$pixels();
    }
}
