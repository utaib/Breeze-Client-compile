package dev.breeze.recorder;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import net.minecraft.client.Minecraft;

/**
 * The OpenGL texture holding the finished frame, for the screen video
 * (Minecraft 26.3: the renderer is RenderPearl, with an OpenGL and a Vulkan
 * backend; only the OpenGL one has a texture Breeze can read).
 */
final class ScreenSource {

    private ScreenSource() {}

    /** The texture's OpenGL name, or 0 when the game does not draw with OpenGL. */
    static int texture(Minecraft mc) {
        GpuTexture t = mc.gameRenderer.mainRenderTarget().getColorTexture();
        return t instanceof GlTexture g ? g.glId() : 0;
    }
}
