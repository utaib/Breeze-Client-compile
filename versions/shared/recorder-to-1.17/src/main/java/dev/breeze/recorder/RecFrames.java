package dev.breeze.recorder;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

/** Reading drawn frames back for a video (Minecraft 1.17: read back at once). */
final class RecFrames {

    private RecFrames() {}

    /** The size of the frames Minecraft draws. */
    static int[] size(Minecraft mc) {
        return new int[]{mc.getMainRenderTarget().width, mc.getMainRenderTarget().height};
    }

    /**
     * Reads the frame just drawn back: the sink gets its RGBA bytes (top
     * row first), or null when the frame is not w by h.
     */
    static void read(Minecraft mc, int w, int h, Consumer<ByteBuffer> sink) {
        hand(Screenshot.takeScreenshot(w, h, mc.getMainRenderTarget()), w, h, sink);
    }

    private static void hand(NativeImage img, int w, int h, Consumer<ByteBuffer> sink) {
        try {
            sink.accept(img.getWidth() == w && img.getHeight() == h ? MemoryUtil.memByteBuffer(RecPixels.pointer(img), w * h * 4) : null);
        } finally {
            img.close();
        }
    }
}
