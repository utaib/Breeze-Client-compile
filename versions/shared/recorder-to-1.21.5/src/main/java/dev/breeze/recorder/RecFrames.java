package dev.breeze.recorder;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

/** Reading drawn frames back for a video (Minecraft 1.21.5: the game reads it back without waiting). */
final class RecFrames {

    private RecFrames() {}

    /** The size of the frames Minecraft draws. */
    static int[] size(Minecraft mc) {
        return new int[]{mc.getMainRenderTarget().width, mc.getMainRenderTarget().height};
    }

    /**
     * The OpenGL texture the game's frame is drawn in (world, hand, HUD and
     * screens, before it is shown), for the screen video; -1 when the game
     * draws with something other than OpenGL.
     */
    static int colorTexture(Minecraft mc) {
        com.mojang.blaze3d.textures.GpuTexture t = mc.getMainRenderTarget().getColorTexture();
        return t instanceof com.mojang.blaze3d.opengl.GlTexture gl ? gl.glId() : -1;
    }

    /**
     * Reads the frame just drawn back: the sink gets its RGBA bytes (top
     * row first), or null when the frame is not w by h.
     */
    static void read(Minecraft mc, int w, int h, Consumer<ByteBuffer> sink) {
        Screenshot.takeScreenshot(mc.getMainRenderTarget(), img -> hand(img, w, h, sink));
    }

    /**
     * Reads the frame just drawn back without waiting for the graphics card
     * (1.21.5 has no scaled read): the game hands the whole frame over once
     * the copy is done, a frame or two later (top row first). Taken once
     * per recording, for its thumbnail.
     */
    static void readLater(Minecraft mc, int width, FrameSink sink) {
        Screenshot.takeScreenshot(mc.getMainRenderTarget(), img -> {
            try {
                sink.accept(MemoryUtil.memByteBuffer(img.getPointer(), img.getWidth() * img.getHeight() * 4), img.getWidth(), img.getHeight(), false);
            } finally {
                img.close();
            }
        });
    }

    /** Nothing to look after each frame here: the game itself delivers the frames readLater asked for. */
    static void poll(Minecraft mc) {}

    private static void hand(NativeImage img, int w, int h, Consumer<ByteBuffer> sink) {
        try {
            sink.accept(img.getWidth() == w && img.getHeight() == h ? MemoryUtil.memByteBuffer(img.getPointer(), w * h * 4) : null);
        } finally {
            img.close();
        }
    }
}
