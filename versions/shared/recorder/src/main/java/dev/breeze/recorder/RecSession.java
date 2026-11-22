package dev.breeze.recorder;

import dev.breeze.BreezeClient;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * One press of the Recorder key: the screen video (always) and, unless the
 * player chose video only, the world replay beside it, both under the same
 * name (recordings/videos/NAME.mp4 and recordings/NAME.breezerec). The
 * second press stops both. The preferences (RecorderPrefs) are chosen on the
 * first press (RecorderSetupScreen) before anything records.
 */
public final class RecSession {

    private static String id;
    private static long startNanos = -1;
    private static boolean replayKept;

    private RecSession() {}

    public static boolean active() {
        return startNanos >= 0;
    }

    /** How long the current recording has run, ms, or -1 (the REC indicator). */
    public static long recordedMs() {
        return startNanos < 0 ? -1 : (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** The replay file name recorded with the video named videoId (same name), or null when it was video only. */
    static String replayFor(String videoId) {
        try {
            Path f = Minecraft.getInstance().gameDirectory.toPath().resolve("recordings").resolve(videoId + RecFormat.EXTENSION);
            return Files.isRegularFile(f) ? f.getFileName().toString() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String lastReplay;

    /** Starts recording with the saved preferences; null when it started, else why not. */
    public static String start(Minecraft mc) {
        if (active()) return null;
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return "Join a world first, then start recording.";
        if (Replay.active() || Exporter.active()) return "A recording is playing; recording starts in a world.";
        RecorderPrefs prefs = RecorderPrefs.get(mc.gameDirectory.toPath());
        String base = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(new Date());
        Path videos = mc.gameDirectory.toPath().resolve("recordings").resolve("videos");
        Path recs = mc.gameDirectory.toPath().resolve("recordings");
        String name = base;
        for (int i = 1; Files.exists(videos.resolve(name + ".mp4")) || Files.exists(recs.resolve(name + RecFormat.EXTENSION)); i++) name = base + "_" + i;
        String replayWhy = null;
        replayKept = false;
        lastReplay = null;
        if (prefs.keepReplay) {
            replayWhy = RecorderHooks.start(mc, name);
            replayKept = replayWhy == null;
            if (replayKept && RecorderHooks.file() != null) lastReplay = RecorderHooks.file().getFileName().toString();
        }
        String videoWhy = ScreenVideo.start(mc, name, prefs);
        if (videoWhy != null && !replayKept) {
            return replayWhy != null ? videoWhy + " " + replayWhy : videoWhy;
        }
        id = name;
        startNanos = System.nanoTime();
        if (videoWhy != null) dev.breeze.shots.ScreenshotChat.say(mc, "Recording the world replay only: " + videoWhy);
        if (replayWhy != null) dev.breeze.shots.ScreenshotChat.say(mc, "Recording the screen video only: " + replayWhy);
        BreezeClient.LOGGER.info("[Breeze] recording {}: screen video {}, world replay {}", name, videoWhy == null ? "on" : "off (" + videoWhy + ")",
                replayKept ? "on" : prefs.keepReplay ? "off (" + replayWhy + ")" : "off (video only)");
        return null;
    }

    /** Stops recording; the chat says what was saved once the video is finished. */
    public static void stop(Minecraft mc) {
        if (!active()) return;
        startNanos = -1;
        String replay = RecorderHooks.stop(mc);
        if (replay == null && replayKept) replay = lastReplay;
        final String replayName = replay;
        final String videoId = id;
        if (!ScreenVideo.active()) {
            if (replayName != null) mc.execute(() -> dev.breeze.compat.Game.message(mc, dev.breeze.shots.ScreenshotChat.recordingLine(replayName)));
            return;
        }
        dev.breeze.shots.ScreenshotChat.say(mc, "Saving the video...");
        ScreenVideo.stop(mc, problem -> {
            if (problem == null) {
                dev.breeze.compat.Game.message(mc, dev.breeze.shots.ScreenshotChat.videoLine(videoId + ".mp4", replayName));
            } else {
                dev.breeze.shots.ScreenshotChat.say(mc, problem);
                if (replayName != null) dev.breeze.compat.Game.message(mc, dev.breeze.shots.ScreenshotChat.recordingLine(replayName));
            }
        });
    }
}
