package dev.breeze.recorder;

import dev.breeze.BreezeClient;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The screen video's frames, taken from the graphics card without making
 * the game wait (Minecraft 1.17 to 26.3; the same code on every version,
 * only the frame's texture is found per version in RecFrames).
 *
 * At the end of each frame the game's main picture (the world, the hand,
 * the HUD, chat, the inventory, the pause menu, settings and Breeze's
 * menus: everything the player sees) is still in its texture. When the
 * frame belongs in the video (FramePacer), one small draw on the graphics
 * card turns it into NV12 at the video's size (BT.709, top row first: the
 * Y plane, then U and V interleaved at half size), which is half the bytes
 * of RGBA, and asks for it to be copied into one of a ring of pixel buffers
 * with a fence. Nothing waits: a later frame looks at the fences, and a
 * buffer the card has finished is mapped and handed to LiveVideo, whose own
 * thread copies the bytes out; the buffer is unmapped on the next frame.
 * When every buffer is busy, the frame is dropped and counted (the video
 * repeats the frame before), so the game never stalls for the recorder.
 *
 * All OpenGL state touched is read first and put back exactly, so the
 * game's (and other mods') idea of what is bound stays true.
 */
final class ScreenCapture {

    private static final int RING = 4;
    /** Frames a buffer may stay unfinished before it is given up (the driver never signalled). */
    private static final int MAX_WAIT_FRAMES = 60;

    private static final String VERTEX = """
            #version 150
            void main() {
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    /**
     * One output byte per pixel of a (w) by (h * 3 / 2) target. Rows 0 to
     * h-1 are luma, the rest chroma (U at even columns, V at odd). Source
     * pixels are fetched by hand (texelFetch, bilinear when scaling), so no
     * sampler state of the game's texture is changed.
     */
    private static final String FRAGMENT = """
            #version 150
            uniform sampler2D src;
            uniform ivec2 srcSize;
            uniform ivec2 dstSize;
            out vec4 outColor;

            vec3 texel(int x, int y) {
                x = clamp(x, 0, srcSize.x - 1);
                y = clamp(y, 0, srcSize.y - 1);
                return texelFetch(src, ivec2(x, srcSize.y - 1 - y), 0).rgb;
            }

            // The colour at a point of the video (pixel units, top left origin).
            vec3 at(vec2 p) {
                vec2 s = p * vec2(srcSize) / vec2(dstSize) - 0.5;
                if (srcSize == dstSize) return texel(int(floor(p.x)), int(floor(p.y)));
                ivec2 i = ivec2(floor(s));
                vec2 f = s - floor(s);
                vec3 a = mix(texel(i.x, i.y), texel(i.x + 1, i.y), f.x);
                vec3 b = mix(texel(i.x, i.y + 1), texel(i.x + 1, i.y + 1), f.x);
                return mix(a, b, f.y);
            }

            void main() {
                ivec2 o = ivec2(gl_FragCoord.xy);
                float v;
                if (o.y < dstSize.y) {
                    vec3 c = at(vec2(o) + 0.5);
                    v = (16.0 + 219.0 * dot(c, vec3(0.2126, 0.7152, 0.0722))) / 255.0;
                } else {
                    int cy = o.y - dstSize.y;
                    int cx = o.x / 2;
                    vec2 base = vec2(float(cx * 2), float(cy * 2));
                    vec3 c = (at(base + vec2(0.5, 0.5)) + at(base + vec2(1.5, 0.5))
                            + at(base + vec2(0.5, 1.5)) + at(base + vec2(1.5, 1.5))) * 0.25;
                    if ((o.x & 1) == 0) v = (128.0 + 224.0 * dot(c, vec3(-0.1146, -0.3854, 0.5))) / 255.0;
                    else v = (128.0 + 224.0 * dot(c, vec3(0.5, -0.4542, -0.0458))) / 255.0;
                }
                outColor = vec4(v, 0.0, 0.0, 1.0);
            }
            """;

    private enum State { FREE, PENDING, MAPPED }

    private static final class Slot implements LiveVideo.Source {
        int pbo;
        long fence;
        State state = State.FREE;
        int repeatBefore;
        int waited;
        long takenAt;
        ByteBuffer mapped;
        final AtomicBoolean copied = new AtomicBoolean();

        @Override
        public void copyTo(byte[] dst) {
            ByteBuffer m = mapped;
            if (m != null) m.get(0, dst, 0, Math.min(dst.length, m.capacity()));
        }

        @Override
        public void release() {
            copied.set(true);
        }
    }

    private final int w, h, bytes;
    private final LiveVideo video;
    private final FramePacer pacer;
    private final Slot[] ring = new Slot[RING];
    private int head;
    private int program, vao, fbo, target;
    private int uSrcSize, uDstSize, uSrc;
    private boolean broken;
    private long renderNanos, renderFrames, mapNanos, mapFrames, worstNanos;

    private ScreenCapture(int w, int h, LiveVideo video, FramePacer pacer) {
        this.w = w;
        this.h = h;
        this.bytes = LiveVideo.nv12Bytes(w, h);
        this.video = video;
        this.pacer = pacer;
    }

    /** Sets up the graphics card's side (render thread); null when this game cannot (no OpenGL texture), and why is logged. */
    static ScreenCapture create(Minecraft mc, int w, int h, LiveVideo video, FramePacer pacer) {
        if (RecFrames.colorTexture(mc) < 0) {
            BreezeClient.LOGGER.warn("[Breeze] screen video: this game does not draw with OpenGL, so the screen cannot be read");
            return null;
        }
        ScreenCapture c = new ScreenCapture(w, h, video, pacer);
        try {
            c.setUp();
            return c;
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] screen video: the graphics card could not be set up: {}", t.toString());
            c.free(true);
            return null;
        }
    }

    private void setUp() {
        int[] saved = saveState();
        try {
            int vs = shader(GL20.GL_VERTEX_SHADER, VERTEX);
            int fs = shader(GL20.GL_FRAGMENT_SHADER, FRAGMENT);
            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vs);
            GL20.glAttachShader(program, fs);
            GL30.glBindFragDataLocation(program, 0, "outColor");
            GL20.glLinkProgram(program);
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
                throw new IllegalStateException("shader link: " + GL20.glGetProgramInfoLog(program));
            }
            uSrc = GL20.glGetUniformLocation(program, "src");
            uSrcSize = GL20.glGetUniformLocation(program, "srcSize");
            uDstSize = GL20.glGetUniformLocation(program, "dstSize");
            vao = GL30.glGenVertexArrays();
            target = GL11.glGenTextures();
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, target);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, w, h * 3 / 2, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            fbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, target, 0);
            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("framebuffer " + status);
            for (int i = 0; i < RING; i++) {
                Slot s = new Slot();
                s.pbo = GL15.glGenBuffers();
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, s.pbo);
                GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, bytes, GL15.GL_STREAM_READ);
                ring[i] = s;
            }
        } finally {
            restoreState(saved);
        }
    }

    private static int shader(int type, String src) {
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, src);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == 0) {
            String log = GL20.glGetShaderInfoLog(s);
            GL20.glDeleteShader(s);
            throw new IllegalStateException("shader: " + log);
        }
        return s;
    }

    // ── Each frame (render thread) ─────────────────────────────────────────

    /**
     * After the frame is drawn: hands over finished buffers, then takes this
     * frame if it belongs in the video. now is the moment it was shown.
     */
    void frame(Minecraft mc, long now) {
        if (broken) return;
        long t0 = System.nanoTime();
        try {
            poll(false);
            int plan = pacer.plan(now);
            if (plan >= 0) {
                Slot s = ring[head];
                if (s.state != State.FREE) {
                    pacer.dropped();
                } else {
                    int tex = RecFrames.colorTexture(mc);
                    int[] size = RecFrames.size(mc);
                    if (tex < 0 || size[0] <= 0 || size[1] <= 0) {
                        pacer.dropped();
                    } else {
                        take(s, tex, size[0], size[1]);
                        s.repeatBefore = plan;
                        s.takenAt = now;
                        head = (head + 1) % RING;
                        pacer.commit(now);
                    }
                }
            }
        } catch (Throwable t) {
            broken = true;
            BreezeClient.LOGGER.warn("[Breeze] screen video: reading frames failed, the video stops here: {}", t.toString());
        }
        long spent = System.nanoTime() - t0;
        renderNanos += spent;
        renderFrames++;
        if (spent > worstNanos && renderFrames > 5) worstNanos = spent;
    }

    /** Converts the frame and asks for it to be copied into the slot's buffer; returns at once. */
    private void take(Slot s, int tex, int srcW, int srcH) {
        int[] saved = saveState();
        try {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
            GL11.glViewport(0, 0, w, h * 3 / 2);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glColorMask(true, true, true, true);
            GL20.glUseProgram(program);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
            GL20.glUniform1i(uSrc, 0);
            GL20.glUniform2i(uSrcSize, srcW, srcH);
            GL20.glUniform2i(uDstSize, w, h);
            GL30.glBindVertexArray(vao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, s.pbo);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glReadPixels(0, 0, w, h * 3 / 2, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, 0L);
            s.fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            s.state = State.PENDING;
            s.waited = 0;
            s.copied.set(false);
        } finally {
            restoreState(saved);
        }
    }

    /**
     * Unmaps buffers already copied out and hands over, in order, those the
     * card has finished. With wait, waits for the card (only when stopping).
     */
    private void poll(boolean wait) {
        for (Slot s : ring) {
            if (s.state == State.MAPPED && s.copied.get()) unmap(s);
        }
        // Oldest first: the slot after the newest taken one.
        for (int k = 0; k < RING; k++) {
            Slot s = ring[(head + k) % RING];
            if (s.state != State.PENDING) continue;
            int r = GL32.glClientWaitSync(s.fence, 0, wait ? 50_000_000L : 0L);
            boolean ready = r == GL32.GL_ALREADY_SIGNALED || r == GL32.GL_CONDITION_SATISFIED;
            if (!ready) {
                if (r == GL32.GL_WAIT_FAILED || ++s.waited > MAX_WAIT_FRAMES || wait) {
                    // Never finished: its slot repeats the frame before.
                    GL32.glDeleteSync(s.fence);
                    s.fence = 0;
                    s.state = State.FREE;
                    video.repeat(s.repeatBefore + 1);
                    continue;
                }
                return;
            }
            GL32.glDeleteSync(s.fence);
            s.fence = 0;
            long m0 = System.nanoTime();
            int prev = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, s.pbo);
            ByteBuffer px = GL30.glMapBufferRange(GL21.GL_PIXEL_PACK_BUFFER, 0, bytes, GL30.GL_MAP_READ_BIT);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, prev);
            mapNanos += System.nanoTime() - m0;
            mapFrames++;
            if (px == null) {
                s.state = State.FREE;
                video.repeat(s.repeatBefore + 1);
                continue;
            }
            s.mapped = px;
            s.state = State.MAPPED;
            s.copied.set(false);
            if (!video.offer(s, s.repeatBefore)) {
                // The encoder is behind: this slot repeats the frame before.
                video.repeat(s.repeatBefore + 1);
                unmap(s);
            }
        }
    }

    private void unmap(Slot s) {
        int prev = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, s.pbo);
        GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, prev);
        s.mapped = null;
        s.state = State.FREE;
    }

    /**
     * Stopping (render thread): waits for the frames still on the card (at
     * most a few tens of milliseconds, once), hands them over, waits for
     * their bytes to be copied out, and frees the card's side.
     */
    void finish() {
        if (!broken) {
            try {
                poll(true);
                long until = System.nanoTime() + 2_000_000_000L;
                while (System.nanoTime() < until) {
                    boolean busy = false;
                    for (Slot s : ring) {
                        if (s.state == State.MAPPED && s.copied.get()) unmap(s);
                        if (s.state == State.MAPPED) busy = true;
                    }
                    if (!busy) break;
                    Thread.onSpinWait();
                }
            } catch (Throwable t) {
                BreezeClient.LOGGER.warn("[Breeze] screen video: the last frames could not be read: {}", t.toString());
            }
        }
        free(false);
    }

    private void free(boolean quiet) {
        try {
            int prev = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
            for (Slot s : ring) {
                if (s == null) continue;
                if (s.state == State.MAPPED && !s.copied.get()) {
                    // Still being copied by the encoder (it is stuck): this buffer is left to the driver.
                    continue;
                }
                if (s.state == State.MAPPED) {
                    GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, s.pbo);
                    GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
                }
                if (s.fence != 0) GL32.glDeleteSync(s.fence);
                if (s.pbo != 0) GL15.glDeleteBuffers(s.pbo);
                s.pbo = 0;
                s.fence = 0;
            }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, prev);
            if (fbo != 0) deleteFramebuffer();
            if (target != 0) GL11.glDeleteTextures(target);
            if (vao != 0) deleteVao();
            if (program != 0) {
                if (GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) == program) GL20.glUseProgram(0);
                GL20.glDeleteProgram(program);
            }
        } catch (Throwable t) {
            if (!quiet) BreezeClient.LOGGER.debug("[Breeze] screen video: freeing: {}", t.toString());
        }
        fbo = target = vao = program = 0;
    }

    private void deleteFramebuffer() {
        int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING), read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        GL30.glDeleteFramebuffers(fbo);
        // Deleting a bound framebuffer binds 0: put back what was bound unless it was this one.
        if (draw != fbo) GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
        if (read != fbo) GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
    }

    private void deleteVao() {
        int bound = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        GL30.glDeleteVertexArrays(vao);
        if (bound != vao) GL30.glBindVertexArray(bound);
    }

    /** What the capture cost the render thread, for the log and the self-test. */
    String stats() {
        return String.format(java.util.Locale.ROOT, "renderThreadUsPerFrame=%.1f worstUs=%d mapUs=%.1f copyMs=%.2f encodeMs=%.2f refused=%d",
                renderFrames == 0 ? 0 : renderNanos / 1000.0 / renderFrames, worstNanos / 1000,
                mapFrames == 0 ? 0 : mapNanos / 1000.0 / mapFrames, video.copyMs(), video.writeMs(), video.refused());
    }

    boolean broken() {
        return broken;
    }

    // ── OpenGL state ───────────────────────────────────────────────────────

    private static final int[] CAPS = {GL11.GL_BLEND, GL11.GL_DEPTH_TEST, GL11.GL_SCISSOR_TEST, GL11.GL_CULL_FACE, GL11.GL_STENCIL_TEST};

    /** Everything this class changes, read from the driver. */
    private static int[] saveState() {
        int[] s = new int[20];
        s[0] = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        s[1] = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        s[2] = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        s[3] = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        s[4] = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        s[5] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        s[6] = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        s[7] = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer vp = stack.mallocInt(4);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);
            for (int i = 0; i < 4; i++) s[8 + i] = vp.get(i);
            ByteBuffer mask = stack.malloc(4);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
            s[12] = (mask.get(0) != 0 ? 1 : 0) | (mask.get(1) != 0 ? 2 : 0) | (mask.get(2) != 0 ? 4 : 0) | (mask.get(3) != 0 ? 8 : 0);
        }
        for (int i = 0; i < CAPS.length; i++) s[13 + i] = GL11.glIsEnabled(CAPS[i]) ? 1 : 0;
        return s;
    }

    private static void restoreState(int[] s) {
        GL20.glUseProgram(s[0]);
        GL30.glBindVertexArray(s[1]);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, s[2]);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, s[3]);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, s[5]);
        GL13.glActiveTexture(s[4]);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, s[6]);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, s[7]);
        GL11.glViewport(s[8], s[9], s[10], s[11]);
        GL11.glColorMask((s[12] & 1) != 0, (s[12] & 2) != 0, (s[12] & 4) != 0, (s[12] & 8) != 0);
        for (int i = 0; i < CAPS.length; i++) {
            if (s[13 + i] != 0) GL11.glEnable(CAPS[i]);
            else GL11.glDisable(CAPS[i]);
        }
    }

    static {
        // MemoryUtil is touched so a missing LWJGL module fails here, at class load, not mid-frame.
        MemoryUtil.class.getName();
    }
}
