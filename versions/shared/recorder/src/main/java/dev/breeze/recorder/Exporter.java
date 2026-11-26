package dev.breeze.recorder;

import dev.breeze.BreezeClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exporting a recording to a video. The recording plays
 * as when watching, but on the video's clock: every frame Minecraft draws is
 * exactly 1/fps of a second later in the game (ExportClockMixin), however
 * long it took to draw, so the video is smooth at any frame rate even on a
 * slow computer (30 to 240 fps, or the player's own rate while recording,
 * up to 360); it only takes longer than the recording to export. Each frame
 * turns the camera to its own moment (Replay.beforeFrame). Each frame is
 * read back without waiting (the game's own screenshot read-back) and handed
 * to ffmpeg through a queue of a few frames.
 *
 * A video larger (or smaller) than the window is drawn at its own size: the
 * game's render targets are resized to it for the export (drawWidth and
 * drawHeight, read by ExportClockMixin when the game resizes them) and back
 * to the window's after, so 4K is drawn at 4K, not scaled up. The encoder
 * is tried at the real size and rate before the first frame; a graphics
 * card encoder that refuses them gives way to x264.
 *
 * Progress shows on the window (ExportOverlay: drawn after the frame is
 * read back, so never in the video) and in its title. Keys and menus do
 * nothing while it runs (the pause menu, chat and the like are closed
 * again at once); only the panel's Cancel stops it and deletes the
 * half-made video.
 */
public final class Exporter {

    private static volatile Exporter current;

    private enum State { WAITING, SIZING, CHECKING, RUNNING, FINISHING }

    /** The size the game draws at during an export, or 0 for the window's (render thread). */
    private static int drawW, drawH;

    /** The width the game's render targets get: the export's while it draws its own size. */
    public static int drawWidth(int window) {
        return drawW > 0 ? drawW : window;
    }

    public static int drawHeight(int window) {
        return drawH > 0 ? drawH : window;
    }

    /**
     * The interface scale for a frame drawn at drawnH instead of windowH, so
     * the interface keeps its size against the picture (26.x, which scales
     * it from the frame's size): the window's scale times the growth, at
     * least 1.
     */
    public static int guiScale(int windowScale, int windowH, int drawnH) {
        if (windowH <= 0 || drawnH == windowH) return windowScale;
        return Math.max(1, (int) Math.round(windowScale * (double) drawnH / windowH));
    }

    private final Path recording;
    private final Path out;
    private final int fps;
    private final VideoEncoder.Quality quality;
    private final String ffmpeg;
    private volatile VideoEncoder.Codec codec;
    private volatile boolean picked;
    private State state = State.WAITING;
    private VideoEncoder encoder;
    private int w, h, outW, outH;
    private final boolean hardware;
    private final int size;
    private long frames;
    private long total;
    private boolean clockOn;
    private long startMs;
    private long startNanos;
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile String result;

    /** What the last export ended with (self-test and the Recordings page): null while none ended. */
    public static volatile String lastResult;
    /** The running export's progress in percent (0 when none). */
    public static volatile long progress;
    public static volatile Path lastVideo;

    /** The frame rate and size the running export makes, for the window title and chat ("1080p, 60 fps"). */
    public static String describe(int outH, int fps) {
        String size = outH == 2160 ? "4K" : outH + "p";
        return size + ", " + fps + " fps";
    }

    private volatile boolean checked;
    private volatile VideoEncoder.Codec confirmed;
    private boolean resized;
    private int windowW, windowH;

    private Exporter(Path recording, Path out, int fps, int size, VideoEncoder.Quality quality, String ffmpeg, ExportSettings settings) {
        this.hardware = settings.hardware;
        this.size = size;
        this.recording = recording;
        this.out = out;
        this.fps = fps;
        this.quality = quality;
        this.ffmpeg = ffmpeg;
    }

    public static boolean active() {
        return current != null;
    }

    /** Starts exporting at the export settings' size; null when it started, else why not. */
    public static String start(Minecraft mc, Path recording, int fps, VideoEncoder.Quality quality) {
        return start(mc, recording, fps, ExportSettings.get(mc.gameDirectory.toPath()).size, quality);
    }

    /**
     * Starts exporting at a size (a video height, 0 for the window's) and
     * frame rate (ExportSettings.AS_RECORDED for the player's own); null when
     * it started, else why not. Outside a world, on the render thread.
     */
    public static String start(Minecraft mc, Path recording, int fpsSetting, int size, VideoEncoder.Quality quality) {
        if (current != null) return "An export is already running.";
        int fps;
        try {
            fps = ExportSettings.resolveFps(fpsSetting, RecReader.readMeta(recording));
        } catch (Exception e) {
            return "That recording could not be read: " + e.getMessage();
        }
        String ffmpeg = Ffmpeg.find();
        if (ffmpeg == null) return Ffmpeg.request();
        Path dir = recording.toAbsolutePath().getParent().resolve("videos");
        String name = recording.getFileName().toString().replace(RecFormat.EXTENSION, "");
        Path out = dir.resolve(name + ".mp4");
        try {
            Files.createDirectories(dir);
            for (int i = 1; Files.exists(out); i++) out = dir.resolve(name + "_" + i + ".mp4");
        } catch (Exception e) {
            return "The videos folder could not be made.";
        }
        String why = Replay.open(mc, recording);
        if (why != null) return why;
        // Held at its start until the encoder is chosen and frames are taken:
        // played meanwhile in real time, the video would begin part way in.
        Replay opened = Replay.current();
        if (opened != null) opened.timeline().pause();
        Exporter e = new Exporter(recording, out, fps, size, quality, ffmpeg, ExportSettings.get(mc.gameDirectory.toPath()));
        lastResult = null;
        lastVideo = null;
        current = e;
        Path scratch = dir;
        Thread t = new Thread(() -> {
            e.codec = VideoEncoder.pick(ffmpeg, VideoEncoder.osKey(System.getProperty("os.name")), scratch, e.hardware);
            e.picked = true;
        }, "Breeze encoder test");
        t.setDaemon(true);
        t.start();
        return null;
    }

    /** The clock the game's frame loop reads: the video's while exporting. */
    public static long millis(long real) {
        Exporter e = current;
        return e != null && e.clockOn ? e.videoMs() : real;
    }

    /** The same clock in nanoseconds, for the playback (Replay). */
    static long nanos() {
        Exporter e = current;
        return e != null && e.clockOn ? e.startNanos + (e.videoMs() - e.startMs) * 1_000_000L : System.nanoTime();
    }

    private long videoMs() {
        return startMs + Math.round(frames * 1000.0 / fps);
    }

    /** After each frame is drawn, before it is shown (render thread). */
    public static void frameDrawn(Minecraft mc) {
        Exporter e = current;
        if (e == null) return;
        try {
            e.step(mc);
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] export failed", t);
            e.end(mc, "The export stopped: " + t.getMessage());
        }
    }

    private void step(Minecraft mc) {
        Replay r = Replay.current();
        if (state == State.FINISHING) return;
        if (r == null) {
            end(mc, "The export stopped: the recording stopped playing.");
            return;
        }
        net.minecraft.client.gui.screens.Screen open = dev.breeze.compat.ActiveScreen.get(mc);
        if (open instanceof PauseScreen || open != null && state == State.RUNNING && !r.isRecordedScreen(open)) {
            // Keys and menus do nothing while exporting: only Cancel on the panel stops it.
            dev.breeze.compat.ActiveScreen.set(mc, null);
        }
        if (cancelAsked) {
            end(mc, "Export cancelled.");
            return;
        }
        ExportOverlay.input(mc, this);
        if (state == State.WAITING) {
            if (!picked || !r.inWorld()) return;
            if (codec == null) {
                end(mc, "ffmpeg could not make a video on this computer.");
                return;
            }
            int[] window = RecGame.frameSize(mc);
            windowW = window[0];
            windowH = window[1];
            int[] o = ExportSettings.outputSize(this.size, windowW, windowH);
            outW = o[0];
            outH = o[1];
            // Drawn at the video's own size: the render targets take it now
            // (or, on versions that size them as they draw, with the next frame).
            if (outW != (windowW & ~1) || outH != (windowH & ~1)) {
                drawW = outW;
                drawH = outH;
                resized = true;
                mc.resizeDisplay();
            }
            state = State.SIZING;
            return;
        }
        if (state == State.SIZING) {
            int[] drawn = RecGame.frameSize(mc);
            w = drawn[0];
            h = drawn[1];
            if (resized && (w != outW || h != outH)) {
                // This game did not take the size (it never should): scaled instead, and said so in the log.
                BreezeClient.LOGGER.warn("[Breeze] the game draws at {}x{}, not {}x{}: the video is scaled", w, h, outW, outH);
            }
            Path scratch = out.getParent();
            VideoEncoder.Codec first = codec;
            Thread t = new Thread(() -> {
                confirmed = VideoEncoder.confirm(ffmpeg, first, scratch, outW, outH, fps);
                checked = true;
            }, "Breeze encoder check");
            t.setDaemon(true);
            t.start();
            state = State.CHECKING;
            return;
        }
        if (state == State.CHECKING) {
            if (!checked) return;
            if (confirmed == null) {
                end(mc, "ffmpeg could not make a " + outW + "x" + outH + " video at " + fps + " fps on this computer.");
                return;
            }
            if (!confirmed.equals(codec)) {
                BreezeClient.LOGGER.info("[Breeze] {} refused {}x{} at {} fps: {} instead", codec.label(), outW, outH, fps, confirmed.label());
            }
            codec = confirmed;
            try {
                encoder = new VideoEncoder(VideoEncoder.command(ffmpeg, codec, quality, w, h, fps, out, false, outW, outH), w, h);
            } catch (Exception ex) {
                end(mc, "ffmpeg could not be started: " + ex.getMessage());
                return;
            }
            r.timeline().setSpeed(1);
            r.timeline().play();
            total = Timeline.frames(r.timeline().durationMs() - r.timeline().positionMs(), fps);
            startMs = RecGame.millis();
            startNanos = System.nanoTime();
            frames = 0;
            clockOn = true;
            state = State.RUNNING;
            BreezeClient.LOGGER.info("[Breeze] exporting {}: window {}x{}, drawn at {}x{}, video {}x{}, {} fps, level {}, {} with {}", recording.getFileName(),
                    windowW, windowH, w, h, outW, outH, fps, VideoEncoder.levelName(VideoEncoder.level(outW, outH, fps)), quality, codec.label());
            return;
        }
        // RUNNING: this frame went into the video when the game finished drawing it
        // (frameRendered); on a game where that hook is missing, it is read here.
        if (!renderHookSeen) capture(mc);
        if (frames % 15 == 0 && frames > 0) {
            long pct = Math.min(100, frames * 100 / Math.max(1, total));
            double spent = (System.nanoTime() - startNanos) / 1e9;
            long left = frames == 0 ? 0 : Math.round(spent / frames * Math.max(0, total - frames));
            progress = pct;
            leftSec = left;
            mc.getWindow().setTitle(String.format(Locale.ROOT, "Breeze: exporting %s, %d%%, about %d:%02d left",
                    describe(outH, fps), pct, left / 60, left % 60));
        }
        if (r.timeline().atEnd() || frames >= total + fps) finish(mc);
    }

    /** Set once GameRenderer.render's end was seen (ExportFrameMixin); then frames are read there. */
    private boolean renderHookSeen;
    private volatile boolean cancelAsked;
    private long leftSec = -1;

    /** Progress panels drawn on the window so far (self-test). */
    public static long overlayPanels() {
        return ExportOverlay.drawn;
    }

    /** Asks the export to stop (the panel's Cancel); it ends on the next frame. */
    public static void cancel() {
        Exporter e = current;
        if (e != null) e.cancelAsked = true;
    }

    /** Seconds left (-1 before known) and percent, for the panel. */
    long leftSeconds() {
        return leftSec;
    }

    String stage() {
        return switch (state) {
            case WAITING, SIZING, CHECKING -> "Getting ready";
            case RUNNING -> "Rendering " + describe(outH, fps);
            case FINISHING -> "Finishing the video";
        };
    }

    /**
     * The game finished drawing a frame, before it is shown (the end of
     * GameRenderer.render, ExportFrameMixin): it goes into the video, and
     * the progress panel is drawn on the window's picture after that.
     */
    public static void frameRendered(Minecraft mc) {
        Exporter e = current;
        if (e == null) return;
        try {
            e.renderHookSeen = true;
            if (e.state == State.RUNNING && e.clockOn) e.capture(mc);
            ExportOverlay.draw(mc, e);
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] export frame: {}", t.toString());
        }
    }

    private void capture(Minecraft mc) {
        Replay r = Replay.current();
        if (r == null || state != State.RUNNING) return;
        inFlight.incrementAndGet();
        VideoEncoder enc = encoder;
        RecGame.readFrame(mc, w, h, pixels -> {
            try {
                if (pixels != null) {
                    byte[] buf = enc.buffer();
                    pixels.get(buf);
                    enc.frame(buf);
                }
            } finally {
                inFlight.decrementAndGet();
            }
        });
        frames++;
    }

    private void finish(Minecraft mc) {
        if (state != State.RUNNING) return;
        VideoEncoder enc = encoder;
        state = State.FINISHING;
        clockOn = false;
        Thread t = new Thread(() -> {
            // The last read-backs arrive on the render thread; wait for them, then for ffmpeg.
            long until = System.currentTimeMillis() + 10_000;
            while (inFlight.get() > 0 && System.currentTimeMillis() < until) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
            String err = enc.finish();
            mc.execute(() -> end(mc, err == null ? null : "The video could not be made: " + err));
        }, "Breeze export finish");
        t.setDaemon(true);
        t.start();
    }

    /** Ends the export: null problem means the video was saved. */
    private void end(Minecraft mc, String problem) {
        if (current != this) return;
        current = null;
        clockOn = false;
        progress = 0;
        ExportOverlay.release();
        if (resized) {
            // Back to the window's size.
            drawW = 0;
            drawH = 0;
            resized = false;
            mc.resizeDisplay();
        }
        if (problem != null) {
            if (encoder != null) encoder.close();
            try {
                Files.deleteIfExists(out);
            } catch (Exception ignored) {}
        }
        lastVideo = problem == null ? out : null;
        lastResult = problem == null
                ? "Video saved: " + out.getFileName() + " (" + frames + " frames, " + outW + "x" + outH + " drawn at " + w + "x" + h + ", " + fps + " fps, " + codec.label() + ")"
                : problem;
        BreezeClient.LOGGER.info("[Breeze] {}", lastResult);
        mc.updateTitle();
        if (Replay.active()) {
            RecGame.toTitle(mc);
            Replay.stop(mc);
        }
    }
}
