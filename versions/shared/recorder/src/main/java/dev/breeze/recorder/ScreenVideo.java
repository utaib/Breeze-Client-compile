package dev.breeze.recorder;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.breeze.BreezeClient;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The screen video of a recording: what the player sees, as an MP4, like a
 * screen recorder inside the game (RecSession starts and stops it; it is
 * always recorded, the world replay is optional beside it).
 *
 * Starting asks the launcher for the sound (AudioLink) and, on its own
 * thread, picks the encoder (the one the export uses, tried once a game and
 * remembered). The first frame after that starts the video; each frame then
 * goes through FramePacer and ScreenCapture to LiveVideo. Stopping reads the
 * last frames, pads the video to the moment Stop was pressed, and on its own
 * thread waits for ffmpeg and the launcher's sound and joins them (Mux):
 * {@code recordings/videos/<name>.mp4} with the game and microphone as two
 * tracks, and {@code <name>.mp4.json} beside it saying what it holds.
 */
public final class ScreenVideo {

    private static volatile ScreenVideo current;
    /** The encoder chosen for this game session (tried once), or null before. */
    private static volatile VideoEncoder.Codec chosen;
    private static volatile boolean chosenHardware;

    private enum State { PICKING, RUNNING, FAILED }

    final Path gameDir, out, raw;
    final String id;
    final int fps;
    final RecorderPrefs prefs;
    private final String ffmpeg;
    private final long startNanos;
    private final double startEpochMs;
    private final boolean audioAsked;
    private volatile State state = State.PICKING;
    private volatile VideoEncoder.Codec codec;
    private volatile boolean picked;
    private volatile String problem;
    private int w, h;
    private LiveVideo video;
    private FramePacer pacer;
    private ScreenCapture capture;
    private long firstFrameNanos = -1;
    private double firstFrameEpochMs;

    /** What the last screen video ended with (self-test, chat): null while none ended. */
    public static volatile String lastResult;
    public static volatile Path lastVideo;
    /** When the last video's first frame was shown (System.nanoTime), for the self-test's moments; -1 before. */
    public static volatile long lastFirstFrameNanos = -1;

    /** Whether the running video has its first frame (the encoder is ready). */
    public static boolean rolling() {
        ScreenVideo v = current;
        return v != null && v.state == State.RUNNING;
    }

    /** The last video's figures (frames, evenness, cost) for the self-test. */
    public static volatile String lastStats = "";

    private ScreenVideo(Path gameDir, String id, RecorderPrefs prefs, String ffmpeg, boolean audioAsked) {
        this.gameDir = gameDir;
        this.id = id;
        this.prefs = prefs;
        this.fps = prefs.fps;
        this.ffmpeg = ffmpeg;
        this.audioAsked = audioAsked;
        Path videos = gameDir.resolve("recordings").resolve("videos");
        this.out = videos.resolve(id + ".mp4");
        this.raw = videos.resolve("." + id + ".recording.mp4");
        this.startNanos = System.nanoTime();
        this.startEpochMs = System.currentTimeMillis();
    }

    public static boolean active() {
        return current != null;
    }

    /** When the running video started (System.nanoTime), for the REC indicator; -1 when none. */
    public static long startedNanos() {
        ScreenVideo v = current;
        return v == null ? -1 : v.startNanos;
    }

    /** Starts the screen video named id; null when it started, else why not (the recording goes on without it). */
    static String start(Minecraft mc, String id, RecorderPrefs prefs) {
        if (current != null) return null;
        String ffmpeg = Ffmpeg.find();
        if (ffmpeg == null) return Ffmpeg.request();
        Path gameDir = mc.gameDirectory.toPath();
        try {
            Files.createDirectories(gameDir.resolve("recordings").resolve("videos"));
        } catch (Exception e) {
            return "The videos folder could not be made.";
        }
        boolean wantSound = prefs.gameSound || prefs.mic;
        boolean asked = false;
        if (wantSound) {
            AudioLink.Status st = AudioLink.status(gameDir);
            if (st.any()) {
                asked = AudioLink.start(gameDir, id, prefs.gameSound && st.game(), prefs.mic && st.mic(), prefs.micDevice);
            } else {
                BreezeClient.LOGGER.info("[Breeze] screen video: no sound this time ({})", st.reason());
            }
        }
        ScreenVideo v = new ScreenVideo(gameDir, id, prefs, ffmpeg, asked);
        int[] win = RecFrames.size(mc);
        v.sizeNow = RecorderPrefs.videoSize(prefs.size, win[0], win[1]);
        v.w = v.sizeNow[0];
        v.h = v.sizeNow[1];
        current = v;
        lastResult = null;
        lastVideo = null;
        Thread t = new Thread(v::pick, "Breeze screen video encoder");
        t.setDaemon(true);
        t.start();
        return null;
    }

    private void pick() {
        try {
            int[] size = sizeNow;
            VideoEncoder.Codec c = chosen != null && chosenHardware == prefs.hardware ? chosen : null;
            if (c == null) {
                c = VideoEncoder.pick(ffmpeg, VideoEncoder.osKey(System.getProperty("os.name")), raw.getParent(), prefs.hardware);
            }
            if (c != null && size != null) c = VideoEncoder.confirm(ffmpeg, c, raw.getParent(), size[0], size[1], fps);
            if (c != null) {
                chosen = c;
                chosenHardware = prefs.hardware;
            }
            codec = c;
        } catch (Throwable t) {
            codec = null;
        }
        picked = true;
    }

    /** The video's size, worked out on the render thread before the encoder thread starts. */
    private volatile int[] sizeNow;

    /** Each frame (render thread, end of the frame). */
    static void frame(Minecraft mc) {
        ScreenVideo v = current;
        if (v == null) return;
        try {
            v.step(mc);
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] screen video failed: {}", t.toString());
            v.fail(mc, "The screen video stopped: " + t.getMessage());
        }
    }

    private void step(Minecraft mc) {
        if (state == State.FAILED) return;
        if (state == State.PICKING) {
            if (!picked) return;
            if (codec == null) {
                fail(mc, "ffmpeg could not make a " + w + "x" + h + " video at " + fps + " fps on this computer.");
                return;
            }
            try {
                video = new LiveVideo(LiveVideo.command(ffmpeg, codec, prefs.quality, w, h, fps, raw), LiveVideo.nv12Bytes(w, h));
            } catch (Exception e) {
                fail(mc, "ffmpeg could not be started: " + e.getMessage());
                return;
            }
            pacer = new FramePacer(fps);
            capture = ScreenCapture.create(mc, w, h, video, pacer);
            if (capture == null) {
                video.close();
                fail(mc, "The screen could not be read on this graphics card.");
                return;
            }
            state = State.RUNNING;
            BreezeClient.LOGGER.info("[Breeze] screen video: {}x{} at {} fps, {} with {}, sound {}", w, h, fps,
                    prefs.quality, codec.label(), audioAsked ? "asked of the launcher" : "none");
        }
        long now = System.nanoTime();
        if (firstFrameNanos < 0) {
            firstFrameNanos = now;
            lastFirstFrameNanos = now;
            firstFrameEpochMs = startEpochMs + (now - startNanos) / 1e6;
        }
        capture.frame(mc, now);
        if (capture.broken() || video.failure() != null) {
            fail(mc, "The screen video stopped: " + (video.failure() != null ? video.failure() : "the graphics card stopped answering"));
        }
    }

    private void fail(Minecraft mc, String why) {
        if (state == State.FAILED) return;
        state = State.FAILED;
        problem = why;
        BreezeClient.LOGGER.warn("[Breeze] {}", why);
        if (capture != null) capture.finish();
        if (video != null) video.close();
        try {
            Files.deleteIfExists(raw);
        } catch (Exception ignored) {}
        if (audioAsked) AudioLink.stop(gameDir, id);
        dev.breeze.shots.ScreenshotChat.say(mc, why);
    }

    /**
     * Stops the video (render thread): the last frames are read, then
     * ffmpeg, the sound and the joining finish on their own thread; done is
     * told the result on the render thread (null problem: saved).
     */
    static void stop(Minecraft mc, Consumer<String> done) {
        ScreenVideo v = current;
        current = null;
        if (v == null) return;
        long stopNanos = System.nanoTime();
        if (v.audioAsked) AudioLink.stop(v.gameDir, v.id);
        if (v.state != State.RUNNING) {
            String why = v.state == State.FAILED ? v.problem : "The screen video had not started yet.";
            v.state = State.FAILED;
            lastResult = why;
            if (done != null) done.accept(why);
            return;
        }
        v.capture.finish();
        int trailing = v.pacer.trailing(stopNanos);
        String captureStats = v.capture.stats();
        Thread t = new Thread(() -> {
            String why = v.complete(trailing, stopNanos, captureStats);
            mc.execute(() -> {
                lastResult = why == null ? "Video saved: " + v.out.getFileName() : why;
                lastVideo = why == null ? v.out : null;
                BreezeClient.LOGGER.info("[Breeze] screen video: {}", lastResult);
                if (done != null) done.accept(why);
            });
        }, "Breeze screen video finish");
        t.setDaemon(true);
        t.start();
    }

    /** Finishes ffmpeg, joins the sound, writes the description; null when saved, else why not. */
    private String complete(int trailing, long stopNanos, String captureStats) {
        String err = video.finish(trailing);
        long slots = pacer.slots() + trailing;
        double duration = slots / (double) fps;
        lastStats = pacer.summary() + " " + captureStats + " videoFrames=" + video.framesWritten() + " durationS="
                + String.format(Locale.ROOT, "%.3f", duration) + " recordedS=" + String.format(Locale.ROOT, "%.3f", (stopNanos - firstFrameNanos) / 1e9);
        BreezeClient.LOGGER.info("[Breeze] screen video figures: {}", lastStats);
        if (err != null) {
            try {
                // A fragmented MP4 cut short still plays up to its last fragment: kept under its real name.
                if (Files.size(raw) > 0) Files.move(raw, out, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignored) {}
            return "The screen video could not be finished: " + err;
        }
        List<Mux.In> tracks = new ArrayList<>();
        List<String> names = new ArrayList<>();
        String soundNote = null;
        if (audioAsked) {
            AudioLink.Result r = AudioLink.await(gameDir, id, 8_000);
            if (r.game() != null) {
                tracks.add(new Mux.In(r.game().file(), (r.game().startEpochMs() - firstFrameEpochMs) / 1000.0, "Game"));
                names.add("game");
            }
            if (r.mic() != null) {
                tracks.add(new Mux.In(r.mic().file(), (r.mic().startEpochMs() - firstFrameEpochMs) / 1000.0, "Microphone"));
                names.add("mic");
            }
            if (r.problem() != null) soundNote = r.problem();
        }
        Path joined = raw.resolveSibling("." + id + ".joined.mp4");
        String joinErr = run(Mux.join(ffmpeg, raw, tracks, duration, joined));
        if (joinErr != null && !tracks.isEmpty()) {
            // The sound could not be joined: the video is kept without it, and the tracks stay for the editor.
            soundNote = "the sound could not be joined: " + joinErr;
            tracks.clear();
            names.clear();
            joinErr = run(Mux.join(ffmpeg, raw, tracks, duration, joined));
        }
        try {
            if (joinErr == null) {
                Files.move(joined, out, StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(raw);
                for (Mux.In t : tracks) Files.deleteIfExists(t.file());
            } else {
                Files.move(raw, out, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            return "The screen video could not be saved: " + e.getMessage();
        }
        JsonObject o = new JsonObject();
        o.addProperty("version", 1);
        o.addProperty("width", w);
        o.addProperty("height", h);
        o.addProperty("fps", fps);
        o.addProperty("frames", slots);
        o.addProperty("durationMs", Math.round(duration * 1000));
        o.addProperty("encoder", codec.label());
        o.addProperty("tracks", String.join(",", names));
        o.addProperty("replay", RecSession.replayFor(id));
        // Where the video's first frame falls on the replay's clock (the recording's start), for renders with sound.
        o.addProperty("videoStartMs", (firstFrameNanos - startNanos) / 1e6);
        if (soundNote != null) o.addProperty("soundNote", soundNote);
        o.addProperty("stats", lastStats);
        try {
            Files.writeString(out.resolveSibling(out.getFileName() + ".json"), new Gson().toJson(o), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
        if (soundNote != null) BreezeClient.LOGGER.warn("[Breeze] screen video sound: {}", soundNote);
        return null;
    }

    /** Runs an ffmpeg command to its end; null when it worked, else its last error line. */
    static String run(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            byte[] o = p.getInputStream().readAllBytes();
            if (!p.waitFor(300, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "ffmpeg did not finish";
            }
            if (p.exitValue() == 0) return null;
            String e = new String(o, StandardCharsets.UTF_8).trim();
            return e.isEmpty() ? "ffmpeg failed" : e.lines().reduce((a, b) -> b).orElse("ffmpeg failed");
        } catch (Exception e) {
            return e.getMessage();
        }
    }
}
