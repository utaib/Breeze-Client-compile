package dev.breeze.recorder;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.Minecraft;

/** The OpenGL texture holding the finished frame, for the screen video (Minecraft 1.21.5 to 26.1.x: the main render target's GlTexture). */
final class ScreenSource {

    private ScreenSource() {}

    /** The texture's OpenGL name, or 0 when the game does not draw with OpenGL. */
    static int texture(Minecraft mc) {
        GpuTexture t = mc.getMainRenderTarget().getColorTexture();
        return t instanceof GlTexture g ? g.glId() : 0;
    }
}
