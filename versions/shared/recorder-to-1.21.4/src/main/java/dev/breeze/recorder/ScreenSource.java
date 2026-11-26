package dev.breeze.recorder;

import net.minecraft.client.Minecraft;

/** The OpenGL texture holding the finished frame, for the screen video (Minecraft 1.17 to 1.21.4: the main render target's colour texture). */
final class ScreenSource {

    private ScreenSource() {}

    static int texture(Minecraft mc) {
        return mc.getMainRenderTarget().getColorTextureId();
    }
}
