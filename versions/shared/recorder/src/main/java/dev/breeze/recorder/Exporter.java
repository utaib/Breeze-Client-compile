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
 * slow computer; it only takes longer than the recording to export. Each
 * frame is read back without waiting (the game's own screenshot read-back)
 * and handed to ffmpeg through a queue of a few frames.
 *
 * Progress shows in the window's title (not in the picture). Escape (the
 * pause menu) cancels and deletes the half-made video.
 */
public final class Exporter {

    private static volatile Exporter current;

    private enum State { WAITING, RUNNING, FINISHING }

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

    private Exporter(Path recording, Path out, int fps, VideoEncoder.Quality quality, String ffmpeg, ExportSettings settings) {
        this.hardware = settings.hardware;
        this.size = settings.size;
        this.recording = recording;
        this.out = out;
        this.fps = fps;
        this.quality = quality;
        this.ffmpeg = ffmpeg;
    }

    public static boolean active() {
        return current != null;
    }

    /** Starts exporting; null when it started, else why not. Outside a world, on the render thread. */
    public static String start(Minecraft mc, Path recording, int fps, VideoEncoder.Quality quality) {
        if (current != null) return "An export is already running.";
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
        Exporter e = new Exporter(recording, out, fps, quality, ffmpeg, ExportSettings.get(mc.gameDirectory.toPath()));
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
        if (dev.breeze.compat.ActiveScreen.get(mc) instanceof PauseScreen) {
            dev.breeze.compat.ActiveScreen.set(mc, null);
            end(mc, "Export cancelled.");
            return;
        }
        if (state == State.WAITING) {
            if (!picked || !r.inWorld()) return;
            if (codec == null) {
                end(mc, "ffmpeg could not make a video on this computer.");
                return;
            }
            int[] size = RecGame.frameSize(mc);
            w = size[0];
            h = size[1];
            int[] o = ExportSettings.outputSize(this.size, w, h);
            outW = o[0];
            outH = o[1];
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
            BreezeClient.LOGGER.info("[Breeze] exporting {}: drawn at {}x{}, video {}x{}, {} fps, {} with {}", recording.getFileName(), w, h,
                    outW, outH, fps, quality, codec.label());
            return;
        }
        // RUNNING: this frame goes into the video.
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
        if (frames % 15 == 0) {
            long pct = Math.min(100, frames * 100 / Math.max(1, total));
            double spent = (System.nanoTime() - startNanos) / 1e9;
            long left = frames == 0 ? 0 : Math.round(spent / frames * Math.max(0, total - frames));
            progress = pct;
            mc.getWindow().setTitle(String.format(Locale.ROOT, "Breeze: exporting %d%%, about %d:%02d left (Escape cancels)", pct, left / 60, left % 60));
        }
        if (r.timeline().atEnd() || frames >= total + fps) {
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

    /** Ends the export: null problem means the video was saved. */
    private void end(Minecraft mc, String problem) {
        if (current != this) return;
        current = null;
        clockOn = false;
        progress = 0;
        if (problem != null) {
            if (encoder != null) encoder.close();
            try {
                Files.deleteIfExists(out);
            } catch (Exception ignored) {}
        }
        lastVideo = problem == null ? out : null;
        lastResult = problem == null
                ? "Video saved: " + out.getFileName() + " (" + frames + " frames, " + w + "x" + h + ", " + fps + " fps, " + codec.label() + ")"
                : problem;
        BreezeClient.LOGGER.info("[Breeze] {}", lastResult);
        mc.updateTitle();
        if (Replay.active()) {
            RecGame.toTitle(mc);
            Replay.stop(mc);
        }
    }
}
