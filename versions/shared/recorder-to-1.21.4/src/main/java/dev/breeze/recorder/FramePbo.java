package dev.breeze.recorder;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;

import java.nio.ByteBuffer;

/**
 * Reading a frame back without waiting for the graphics card (Minecraft 1.17
 * to 1.21.4, which read frames back only at once): the drawn picture is
 * copied into a pixel buffer object on the card, a fence marks the copy, and
 * the pixels are taken once the fence has passed, a frame or more later
 * (RecFrames.poll, each frame). One read at a time; rows bottom first.
 */
final class FramePbo {

    private static int buffer;
    private static long fence;
    private static int width, height, waited;
    private static FrameSink pending;

    private FramePbo() {}

    static void read(RenderTarget target, FrameSink sink) {
        if (pending != null) {
            sink.accept(null, 0, 0, true);
            return;
        }
        int w = target.width, h = target.height;
        try {
            buffer = GL15.glGenBuffers();
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffer);
            GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, (long) w * h * 4, GL15.GL_STREAM_READ);
            GlStateManager._bindTexture(target.getColorTextureId());
            // Into the bound buffer: returns at once, the card copies when it gets there.
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            width = w;
            height = h;
            waited = 0;
            pending = sink;
        } catch (Throwable t) {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            release();
            sink.accept(null, 0, 0, true);
        }
    }

    /** Hands the pixels over once the card has copied them; never waits for it. */
    static void poll() {
        FrameSink sink = pending;
        if (sink == null) return;
        int state = GL32.glClientWaitSync(fence, 0, 0L);
        boolean ready = state == GL32.GL_ALREADY_SIGNALED || state == GL32.GL_CONDITION_SATISFIED;
        if (!ready && state != GL32.GL_WAIT_FAILED && ++waited < 300) return;
        pending = null;
        if (!ready) {
            // The card never got there (or the driver cannot say): no picture, nothing waited for.
            release();
            sink.accept(null, 0, 0, true);
            return;
        }
        ByteBuffer px = null;
        try {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffer);
            px = GL30.glMapBufferRange(GL21.GL_PIXEL_PACK_BUFFER, 0, (long) width * height * 4, GL30.GL_MAP_READ_BIT);
            sink.accept(px, width, height, true);
        } finally {
            if (px != null) GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            release();
        }
    }

    private static void release() {
        if (fence != 0) GL32.glDeleteSync(fence);
        if (buffer != 0) GL15.glDeleteBuffers(buffer);
        fence = 0;
        buffer = 0;
    }
}
