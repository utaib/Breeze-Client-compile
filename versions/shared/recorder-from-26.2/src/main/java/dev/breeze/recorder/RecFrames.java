package dev.breeze.recorder;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

/** Reading drawn frames back for a video (Minecraft 26.2 and later (the main render target is the game renderer's): the game reads it back without waiting). */
final class RecFrames {

    private RecFrames() {}

    /** The size of the frames Minecraft draws. */
    static int[] size(Minecraft mc) {
        return new int[]{mc.gameRenderer.mainRenderTarget().width, mc.gameRenderer.mainRenderTarget().height};
    }

    /**
     * Reads the frame just drawn back: the sink gets its RGBA bytes (top
     * row first), or null when the frame is not w by h.
     */
    static void read(Minecraft mc, int w, int h, Consumer<ByteBuffer> sink) {
        Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), img -> hand(img, w, h, sink));
    }

    /**
     * Reads the frame just drawn back, scaled down about to the given width,
     * without waiting for the graphics card: the game copies it into a
     * buffer on the card and hands it over once the copy is done (a frame or
     * two later), copying only the scaled-down pixels (top row first).
     */
    static void readLater(Minecraft mc, int width, FrameSink sink) {
        int scale = Math.max(1, mc.gameRenderer.mainRenderTarget().width / Math.max(1, width));
        Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), scale, img -> {
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
