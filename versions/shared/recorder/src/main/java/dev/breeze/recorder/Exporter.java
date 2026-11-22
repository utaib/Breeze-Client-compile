package dev.breeze.recorder;

import dev.breeze.BreezeClient;
import net.minecraft.client.Minecraft;

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
 * While it runs, Breeze's export screen (ExportScreen) shows the progress
 * and a Cancel button, and stays open: keys, the pause menu, losing focus
 * and other screens cannot stop the export (any other screen is closed again
 * at once); only its own Cancel does. Each frame is read once the world and
 * the hand are drawn (ExportCaptureMixin), before the HUD and the export
 * screen, so neither is in the video. Progress also shows in the window's
 * title.
 *
 * A render may use another resource pack (the export editor's choice): the
 * pack is turned on before the recording opens and the player's own packs
 * are put back when the export ends.
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

    /** The export screen kept open while the export runs. */
    private dev.breeze.menu.ExportScreen screen;
    /** Whether frames are read after the world is drawn (ExportCaptureMixin answered), or at the end of the frame. */
    private boolean worldHook, readThisFrame;
    private int framesWithoutHook;
    /** Packs to put back when the export ends (top first), or null when the export did not change them. */
    private java.util.List<String> packsBefore;
    /** Told the result on the render thread, back at the title screen (the export editor reopens). */
    private java.util.function.Consumer<String> after;
    private volatile boolean cancelled;

    /** Cancels the running export (its screen's Cancel button). */
    public static void cancel() {
        Exporter e = current;
        if (e != null) e.cancelled = true;
    }

    /** The running export's name and summary for its screen ("1080p, 60 fps"), or null. */
    public static String runningSummary() {
        Exporter e = current;
        if (e == null) return null;
        return e.recording.getFileName().toString().replace(RecFormat.EXTENSION, "") + (e.outH > 0 ? ": " + describe(e.outH, e.fps) : "");
    }

    /** The running export's stage for its screen. */
    public static String runningStage() {
        Exporter e = current;
        if (e == null) return "";
        return switch (e.state) {
            case WAITING -> "Opening the recording";
            case SIZING, CHECKING -> "Getting the encoder ready";
            case RUNNING -> "Rendering";
            case FINISHING -> "Finishing the video";
        };
    }

    /** Seconds left in the running export, or -1 when not known yet. */
    public static volatile long secondsLeft = -1;

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
        return start(mc, recording, fpsSetting, size, quality, null, null, null);
    }

    /**
     * The same with a resource pack to render with (a pack id from
     * Minecraft's list, or null for the packs on now), the video's file
     * (null: recordings/videos/NAME.mp4), and what to do with the result
     * once back at the title screen (null problem: saved).
     */
    public static String start(Minecraft mc, Path recording, int fpsSetting, int size, VideoEncoder.Quality quality,
                               String pack, Path outFile, java.util.function.Consumer<String> after) {
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
        Path out = outFile != null ? outFile : dir.resolve(name + ".mp4");
        try {
            Files.createDirectories(out.toAbsolutePath().getParent());
            if (outFile == null) for (int i = 1; Files.exists(out); i++) out = dir.resolve(name + "_" + i + ".mp4");
        } catch (Exception e) {
            return "The videos folder could not be made.";
        }
        java.util.List<String> before = null;
        if (pack != null) {
            before = dev.breeze.packs.PackMenu.selectedTopFirst(mc);
            java.util.List<String> want = new java.util.ArrayList<>();
            want.add(pack);
            dev.breeze.packs.PackMenu.apply(mc, want);
            BreezeClient.LOGGER.info("[Breeze] rendering with the resource pack {} (the player's packs come back after)", pack);
        }
        String why = Replay.open(mc, recording);
        if (why != null) {
            if (before != null) dev.breeze.packs.PackMenu.apply(mc, before);
            return why;
        }
        // Held at its start until the encoder is chosen and frames are taken:
        // played meanwhile in real time, the video would begin part way in.
        Replay opened = Replay.current();
        if (opened != null) opened.timeline().pause();
        Exporter e = new Exporter(recording, out, fps, size, quality, ffmpeg, ExportSettings.get(mc.gameDirectory.toPath()));
        e.packsBefore = before;
        e.after = after;
        secondsLeft = -1;
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
        if (cancelled) {
            end(mc, "Export cancelled.");
            return;
        }
        // Once the recorded world is on screen the export screen stays: keys,
        // the pause menu (focus lost) and other screens are closed again.
        if (screen != null && dev.breeze.compat.ActiveScreen.get(mc) != screen) {
            net.minecraft.client.gui.screens.Screen other = dev.breeze.compat.ActiveScreen.get(mc);
            if (other != null) BreezeClient.LOGGER.info("[Breeze] export: {} was closed again (only Cancel stops an export)", other.getClass().getSimpleName());
            dev.breeze.compat.ActiveScreen.set(mc, screen);
        }
        if (state == State.WAITING) {
            if (!picked || !r.inWorld()) return;
            if (codec == null) {
                end(mc, "ffmpeg could not make a video on this computer.");
                return;
            }
            screen = new dev.breeze.menu.ExportScreen();
            dev.breeze.compat.ActiveScreen.set(mc, screen);
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
        // RUNNING: this frame went into the video when the world was drawn
        // (worldDrawn); a version without that hook reads the finished frame.
        if (!readThisFrame) {
            if (!worldHook && ++framesWithoutHook >= 3) {
                if (framesWithoutHook == 3) BreezeClient.LOGGER.info("[Breeze] export: frames are read at the end of each frame on this version");
                readFrame(mc);
            }
        }
        readThisFrame = false;
        if (frames % 15 == 0 && frames > 0) {
            long pct = Math.min(100, frames * 100 / Math.max(1, total));
            double spent = (System.nanoTime() - startNanos) / 1e9;
            long left = frames == 0 ? 0 : Math.round(spent / frames * Math.max(0, total - frames));
            progress = pct;
            secondsLeft = left;
            mc.getWindow().setTitle(String.format(Locale.ROOT, "Breeze: exporting %s, %d%%, about %d:%02d left (Escape cancels)",
                    describe(outH, fps), pct, left / 60, left % 60).replace(" (Escape cancels)", ""));
        }
        if (r.timeline().atEnd() || frames >= total + fps) {
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
    }

    /** After the world and hand are drawn, before the HUD and screens (ExportCaptureMixin): this frame goes into the video. */
    public static void worldDrawn(Minecraft mc) {
        Exporter e = current;
        if (e == null || e.state != State.RUNNING || Replay.current() == null) return;
        try {
            e.worldHook = true;
            e.readFrame(mc);
            e.readThisFrame = true;
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] export: a frame could not be read", t);
        }
    }

    private void readFrame(Minecraft mc) {
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

    /** Ends the export: null problem means the video was saved. */
    private void end(Minecraft mc, String problem) {
        if (current != this) return;
        current = null;
        clockOn = false;
        progress = 0;
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
        secondsLeft = -1;
        mc.updateTitle();
        screen = null;
        if (Replay.active()) {
            RecGame.toTitle(mc);
            Replay.stop(mc);
        }
        if (packsBefore != null) {
            dev.breeze.packs.PackMenu.apply(mc, packsBefore);
            packsBefore = null;
        }
        if (after != null) {
            java.util.function.Consumer<String> a = after;
            after = null;
            String p = problem;
            mc.execute(() -> a.accept(p));
        }
    }
}
