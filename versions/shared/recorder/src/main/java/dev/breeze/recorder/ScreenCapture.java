package dev.breeze.recorder;

import dev.breeze.BreezeClient;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * The screen video: exactly what the player sees (the world, their hand,
 * HUD, chat, inventory, the pause menu, settings and Breeze's own screens),
 * like OBS but inside the game. Every frame, after it is drawn and shown
 * (the tail of Minecraft.runTick, where the finished picture is still in the
 * main render target), the picture is copied on the graphics card into one
 * of a ring of pixel buffers (scaled down there first when the video is
 * smaller than the window), with a fence; frames are taken out of the ring
 * once their fence has passed, a frame or two later, so the game never waits
 * for the card. Each frame keeps the moment it was shown, and FramePacer
 * makes the video's even frames from them (the frame shown nearest each
 * tick), so the video moves as smoothly as the game looked and lasts the
 * real time. ffmpeg encodes on its own process (LiveEncoder: never waited
 * for; a frame it cannot take in time repeats the one before) into a
 * fragmented MP4 that plays even if the game stops without saving.
 *
 * Every OpenGL binding Breeze touches is read first and put back after, so
 * the game's own state (and its caches of it) never changes. Render thread
 * only.
 */
public final class ScreenCapture {

    private static final int RING = 3;

    private static ScreenCapture current;
    /** The encoder this computer uses (picked once, off the render thread), or null until known. */
    private static volatile VideoEncoder.Codec codec;
    private static volatile boolean picking;
    private static volatile String lastResult;
    private static volatile Path lastVideo;
    private static volatile String lastStats = "";

    private final Path out;
    private final int fps;
    private final VideoEncoder.Quality quality;
    private final String ffmpeg;
    private final int w, h;
    private volatile LiveEncoder enc;
    private volatile String startProblem;
    private boolean triedSoftware;
    private final FramePacer pacer;
    private ByteBuffer held;
    private boolean haveHeld;
    private final long startedAt = System.nanoTime();
    private long firstAt;

    // OpenGL objects (render thread).
    private int srcFbo, attachedTex, smallFbo, smallRb;
    private final int[] pbo = new int[RING];
    private final long[] fence = new long[RING];
    private final long[] stamp = new long[RING];
    private final boolean[] busy = new boolean[RING];
    private int head, tail;
    private boolean glBroken;
    private int noTexture;

    // Cost and counts.
    private long frames, ringFull, noBuffer, workNanos, worstNanos, waited;

    private ScreenCapture(Path out, int fps, VideoEncoder.Quality quality, String ffmpeg, int w, int h) {
        this.out = out;
        this.fps = fps;
        this.quality = quality;
        this.ffmpeg = ffmpeg;
        this.w = w;
        this.h = h;
        this.pacer = new FramePacer(fps);
    }

    public static boolean active() {
        return current != null;
    }

    /** The video being made, or null. */
    public static Path file() {
        ScreenCapture c = current;
        return c == null ? null : c.out;
    }

    /** What the last screen video ended with (null while none ended), for chat and the self-test. */
    public static String lastResult() {
        return lastResult;
    }

    public static Path lastVideo() {
        return lastVideo;
    }

    /** Milliseconds of screen video so far (from its first frame), or -1 while none has been taken (self-test). */
    public static long videoMs() {
        ScreenCapture c = current;
        if (c == null || c.firstAt == 0) return -1;
        return (System.nanoTime() - c.firstAt) / 1_000_000L;
    }

    /** Video frames the last screen video should hold (self-test), or -1. */
    public static volatile long lastTicks = -1;

    /** The last screen video's counts: frames, evenness, cost per frame (self-test and log). */
    public static String lastStats() {
        return lastStats;
    }

    /**
     * Looks for ffmpeg and picks this computer's encoder in the background,
     * so the first recording starts at once. Asks the launcher for ffmpeg
     * when there is none.
     */
    public static void prepare(Minecraft mc) {
        if (codec != null || picking) return;
        picking = true;
        boolean hardware = CaptureSettings.get(mc.gameDirectory.toPath()).hardware;
        Path scratch = mc.gameDirectory.toPath().resolve("recordings");
        Thread t = new Thread(() -> {
            try {
                String ff = ffmpegPath != null ? ffmpegPath : Ffmpeg.find();
                if (ff == null) return;
                ffmpegPath = ff;
                Files.createDirectories(scratch);
                codec = VideoEncoder.pick(ff, VideoEncoder.osKey(System.getProperty("os.name")), scratch, hardware);
                BreezeClient.LOGGER.info("[Breeze] screen video encoder: {}", codec == null ? "none works" : codec.label());
            } catch (Exception e) {
                BreezeClient.LOGGER.warn("[Breeze] screen video: no encoder: {}", e.toString());
            } finally {
                picking = false;
            }
        }, "Breeze screen video encoder test");
        t.setDaemon(true);
        t.start();
    }

    /** ffmpeg, once found (off the render thread where it can be). */
    private static volatile String ffmpegPath;

    /** Forgets the picked encoder (the preference for the graphics card changed). */
    public static void forgetEncoder() {
        if (!picking) codec = null;
    }

    /**
     * Starts the screen video into recordings/videos/{@code base}.mp4; null
     * when it started, else why not (ffmpeg missing: the launcher is asked
     * for it). The encoder starts on its own thread once it is known; the
     * video starts with its first frame after that.
     */
    public static String start(Minecraft mc, String base) {
        if (current != null) return null;
        CaptureSettings s = CaptureSettings.get(mc.gameDirectory.toPath());
        String ff = ffmpegPath != null ? ffmpegPath : Ffmpeg.find();
        if (ff == null) return Ffmpeg.request();
        ffmpegPath = ff;
        int[] src = RecFrames.size(mc);
        int[] v = CaptureSettings.videoSize(s.size, src[0], src[1]);
        Path dir = mc.gameDirectory.toPath().resolve("recordings").resolve("videos");
        Path out = dir.resolve(base + ".mp4");
        try {
            Files.createDirectories(dir);
            for (int i = 1; Files.exists(out); i++) out = dir.resolve(base + "_" + i + ".mp4");
        } catch (Exception e) {
            return "The videos folder could not be made.";
        }
        ScreenCapture c = new ScreenCapture(out, s.fps, s.quality, ff, v[0], v[1]);
        lastResult = null;
        lastVideo = null;
        current = c;
        prepare(mc);
        Thread t = new Thread(c::openEncoder, "Breeze screen video start");
        t.setDaemon(true);
        t.start();
        return null;
    }

    private void openEncoder() {
        long until = System.currentTimeMillis() + 30_000;
        while (codec == null && picking && System.currentTimeMillis() < until) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                return;
            }
        }
        VideoEncoder.Codec c = codec;
        if (c == null) {
            startProblem = "ffmpeg could not make a video on this computer.";
            return;
        }
        open(c);
    }

    private void open(VideoEncoder.Codec c) {
        try {
            int cap = LiveEncoder.capacityFor((long) w * h * 4);
            enc = new LiveEncoder(VideoEncoder.liveCommand(ffmpeg, c, quality, w, h, fps, out), w, h, cap);
            BreezeClient.LOGGER.info("[Breeze] screen video: {}x{}, {} fps, {} with {}, into {}", w, h, fps, quality, c.label(), out.getFileName());
        } catch (Exception e) {
            startProblem = "ffmpeg could not be started: " + e.getMessage();
        }
    }

    // ── Each frame (render thread) ─────────────────────────────────────────

    /** After each frame is shown (RecorderHooks.frame). */
    public static void frame(Minecraft mc) {
        ScreenCapture c = current;
        if (c == null) return;
        long now = System.nanoTime();
        try {
            c.step(mc, now);
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] screen video stopped", t);
            c.glBroken = true;
            stop(mc);
        }
        long spent = System.nanoTime() - now;
        c.workNanos += spent;
        if (spent > c.worstNanos) c.worstNanos = spent;
    }

    private void step(Minecraft mc, long now) {
        if (startProblem != null) {
            String why = startProblem;
            stop(mc);
            dev.breeze.shots.ScreenshotChat.say(mc, "The screen video stopped: " + why);
            return;
        }
        LiveEncoder e = enc;
        if (e == null) return;
        if (e.failure() != null && e.written() == 0 && !triedSoftware && codec != null && codec.hardware()) {
            // The graphics card's encoder refused this size or rate: the processor's instead.
            triedSoftware = true;
            BreezeClient.LOGGER.info("[Breeze] screen video: {} refused {}x{} at {} fps; x264 instead", codec.label(), w, h, fps);
            e.close();
            open(VideoEncoder.X264);
            return;
        }
        collect(e, false);
        issue(mc, now);
    }

    /** Takes every frame whose copy has finished out of the ring, oldest first; with wait, waits for each. */
    private void collect(LiveEncoder e, boolean wait) {
        while (busy[tail]) {
            int state = GL32.glClientWaitSync(fence[tail], wait ? GL32.GL_SYNC_FLUSH_COMMANDS_BIT : 0, wait ? 100_000_000L : 0L);
            boolean ready = state == GL32.GL_ALREADY_SIGNALED || state == GL32.GL_CONDITION_SATISFIED;
            if (!ready && !wait && state != GL32.GL_WAIT_FAILED) return;
            if (wait && !ready) waited++;
            ByteBuffer dst = ready ? e.take() : null;
            if (ready && dst == null) noBuffer++;
            if (dst != null) {
                int prevPack = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[tail]);
                ByteBuffer src = GL30.glMapBufferRange(GL21.GL_PIXEL_PACK_BUFFER, 0, (long) w * h * 4, GL30.GL_MAP_READ_BIT);
                if (src != null) {
                    // Rows come bottom first: the video's go top first.
                    long s = MemoryUtil.memAddress(src), d = MemoryUtil.memAddress(dst);
                    long row = (long) w * 4;
                    for (int y = 0; y < h; y++) MemoryUtil.memCopy(s + row * (h - 1 - y), d + row * y, row);
                    GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
                } else {
                    e.release(dst);
                    dst = null;
                    noBuffer++;
                }
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, prevPack);
            }
            GL32.glDeleteSync(fence[tail]);
            fence[tail] = 0;
            busy[tail] = false;
            long at = stamp[tail];
            tail = (tail + 1) % RING;
            pace(e, at, dst);
        }
    }

    /** A frame shown at {@code at} came out of the ring (pixels null when they could not be kept). */
    private void pace(LiveEncoder e, long at, ByteBuffer pixels) {
        if (frames == 0) firstAt = at;
        frames++;
        int n = pacer.add(at);
        if (haveHeld && held != null) e.submit(held, n);
        else e.repeatLast(n);
        held = pixels;
        haveHeld = true;
    }

    /** Copies the frame just shown into the next free buffer of the ring, without waiting. */
    private void issue(Minecraft mc, long now) {
        if (glBroken) return;
        if (busy[head]) {
            ringFull++;
            return;
        }
        int tex = ScreenSource.texture(mc);
        if (tex == 0) {
            if (frames == 0 && ++noTexture >= 30) {
                startProblem = "it works with Minecraft's OpenGL renderer, and this game draws with another one. The world replay still records.";
            }
            return;
        }
        int[] src = RecFrames.size(mc);
        int sw = src[0], sh = src[1];
        int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevPack = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int prevAlign = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        int prevRowLen = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
        int prevSkipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
        int prevSkipPx = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
        try {
            if (srcFbo == 0) srcFbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, srcFbo);
            if (attachedTex != tex) {
                GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);
                attachedTex = tex;
            }
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            if (sw != w || sh != h) {
                // A smaller video (or a window resized while recording): scaled on the card.
                scaleInto(sw, sh);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, smallFbo);
                GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            }
            if (pbo[head] == 0) {
                pbo[head] = GL15.glGenBuffers();
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[head]);
                GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, (long) w * h * 4, GL15.GL_STREAM_READ);
            } else {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[head]);
            }
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
            // Into the bound buffer: returns at once, the card copies when it gets there.
            GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
            fence[head] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            stamp[head] = now;
            busy[head] = true;
            head = (head + 1) % RING;
        } finally {
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, prevAlign);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, prevRowLen);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, prevSkipRows);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, prevSkipPx);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, prevPack);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
        }
    }

    /** Draws the read framebuffer (sw by sh) scaled into the video-sized one, on the card. */
    private void scaleInto(int sw, int sh) {
        if (smallFbo == 0) {
            int prevRb = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
            smallRb = GL30.glGenRenderbuffers();
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, smallRb);
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL11.GL_RGBA8, w, h);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, prevRb);
            smallFbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, smallFbo);
            GL30.glFramebufferRenderbuffer(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_RENDERBUFFER, smallRb);
        }
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, smallFbo);
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        if (scissor) GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL30.glBlitFramebuffer(0, 0, sw, sh, 0, 0, w, h, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
        if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
    }

    // ── Stop ───────────────────────────────────────────────────────────────

    /**
     * Stops the screen video: the frames still on the card are taken (a
     * few milliseconds at most), the last frame takes its ticks, and ffmpeg
     * finishes on its own thread. Chat then offers to open the video.
     */
    public static void stop(Minecraft mc) {
        ScreenCapture c = current;
        if (c == null) return;
        current = null;
        LiveEncoder e = c.enc;
        try {
            if (e != null && !c.glBroken) c.collect(e, true);
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] screen video: the last frames were lost: {}", t.toString());
        }
        if (e != null) {
            int n = c.pacer.finish();
            if (c.haveHeld && c.held != null) e.submit(c.held, n);
            else e.repeatLast(n);
        }
        c.release();
        long ms = (System.nanoTime() - c.startedAt) / 1_000_000L;
        String stats = c.stats(e);
        lastTicks = c.pacer.ticks();
        lastStats = stats;
        BreezeClient.LOGGER.info("[Breeze] screen video stopped after {} ms: {}", ms, stats);
        if (e == null) {
            lastResult = c.startProblem != null ? c.startProblem : "The screen video had not started yet.";
            try {
                Files.deleteIfExists(c.out);
            } catch (Exception ignored) {}
            return;
        }
        Thread t = new Thread(() -> {
            String err = e.finish();
            mc.execute(() -> {
                lastVideo = err == null ? c.out : null;
                lastResult = err == null ? "Video saved: " + c.out.getFileName() : "The video could not be made: " + err;
                BreezeClient.LOGGER.info("[Breeze] screen video: {}", lastResult);
                if (err == null) dev.breeze.compat.Game.message(mc, dev.breeze.shots.ScreenshotChat.videoLine(c.out.getFileName().toString()));
                else dev.breeze.shots.ScreenshotChat.say(mc, lastResult);
            });
        }, "Breeze screen video finish");
        t.setDaemon(true);
        t.start();
    }

    private String stats(LiveEncoder e) {
        double avgUs = frames == 0 ? 0 : workNanos / 1000.0 / Math.max(1, frames);
        return String.format(Locale.ROOT, "size=%dx%d fps=%d %s captured=%d ringFull=%d noBuffer=%d encoderDropped=%d written=%d waitedAtStop=%d captureUsAvg=%.0f captureUsWorst=%d",
                w, h, fps, pacer.summary(), frames, ringFull, noBuffer, e == null ? 0 : e.dropped(), e == null ? 0 : e.written(), waited,
                avgUs, worstNanos / 1000);
    }

    /** Frees the card's buffers (render thread). */
    private void release() {
        try {
            for (int i = 0; i < RING; i++) {
                if (fence[i] != 0) GL32.glDeleteSync(fence[i]);
                if (pbo[i] != 0) GL15.glDeleteBuffers(pbo[i]);
                fence[i] = 0;
                pbo[i] = 0;
                busy[i] = false;
            }
            if (srcFbo != 0) GL30.glDeleteFramebuffers(srcFbo);
            if (smallFbo != 0) GL30.glDeleteFramebuffers(smallFbo);
            if (smallRb != 0) GL30.glDeleteRenderbuffers(smallRb);
        } catch (Throwable ignored) {
            // The context is gone (the game is closing): nothing to free.
        }
        srcFbo = smallFbo = smallRb = 0;
    }
}
