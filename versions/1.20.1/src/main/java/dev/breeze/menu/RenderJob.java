package dev.breeze.menu;

import dev.breeze.BreezeClient;
import dev.breeze.recorder.ExportSettings;
import dev.breeze.recorder.Exporter;
import dev.breeze.recorder.Ffmpeg;
import dev.breeze.recorder.RecReader;
import dev.breeze.recorder.RecorderHooks;
import dev.breeze.recorder.SoundMux;
import dev.breeze.recorder.VideoProbe;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The export editor's Render: the world replay drawn again, frame by frame,
 * with a resource pack chosen from the installed ones. The pack goes on top
 * of the packs on now (Minecraft reloads its resources), the export runs as
 * any export does (Exporter: leaves the world first, renders at the export
 * settings' rate and size), and afterwards the packs are put back as they
 * were, the screen video's sound tracks are added to the render in time
 * (SoundMux.carry), and the editor opens on the new video.
 */
public final class RenderJob {

    private enum State { RELOADING, ASKED, RUNNING }

    private static RenderJob current;
    /** What the last render ended with (self-test): null while none ended. */
    public static volatile String lastResult;
    public static volatile Path lastVideo;

    private final Path recording, soundFrom;
    private final String pack;
    private final List<String> before = new ArrayList<>();
    private State state;
    private long since;

    private RenderJob(Path recording, String pack, Path soundFrom) {
        this.recording = recording;
        this.pack = pack;
        this.soundFrom = soundFrom;
    }

    public static boolean active() {
        return current != null;
    }

    /** Starts a render; null when it started, else why not. */
    public static String start(Minecraft mc, Path recording, String packId, Path soundFrom) {
        if (current != null || Exporter.active()) return "An export is already running.";
        if (RecorderHooks.sessionActive()) return "Stop recording first.";
        RenderJob j = new RenderJob(recording, packId, soundFrom);
        lastResult = null;
        lastVideo = null;
        if (packId != null) {
            try {
                com.google.gson.JsonObject list = dev.breeze.packs.PackMenu.json(mc, false);
                for (com.google.gson.JsonElement e : list.getAsJsonArray("selected")) j.before.add(e.getAsString());
                List<String> wanted = new ArrayList<>();
                wanted.add(packId);
                for (String id : j.before) if (!id.equals(packId)) wanted.add(id);
                dev.breeze.packs.PackMenu.apply(mc, wanted);
                BreezeClient.LOGGER.info("[Breeze] render: {} on top of {}", packId, j.before);
            } catch (Throwable t) {
                return "That pack could not be turned on: " + t.getMessage();
            }
        }
        j.state = State.RELOADING;
        j.since = System.currentTimeMillis();
        current = j;
        return null;
    }

    /** Each tick (RecorderHooks.tick). */
    public static void tick(Minecraft mc) {
        RenderJob j = current;
        if (j == null) return;
        try {
            j.step(mc);
        } catch (Throwable t) {
            j.end(mc, "The render stopped: " + t.getMessage(), null);
        }
    }

    private void step(Minecraft mc) {
        long in = System.currentTimeMillis() - since;
        switch (state) {
            case RELOADING -> {
                // The pack's resources load first (a second or two), so the very first frame has them.
                if (dev.breeze.compat.ActiveScreen.overlay(mc) != null && in < 120_000) return;
                if (in < 500) return;
                ExportSettings es = ExportSettings.get(mc.gameDirectory.toPath());
                String why = RecorderHooks.export(mc, recording, es.fps, es.size, es.quality);
                if (why != null) {
                    end(mc, why, null);
                    return;
                }
                state = State.ASKED;
                since = System.currentTimeMillis();
            }
            case ASKED -> {
                if (Exporter.active()) {
                    state = State.RUNNING;
                } else if (Exporter.lastResult != null || in > 180_000) {
                    end(mc, Exporter.lastResult != null ? Exporter.lastResult : "The render did not start.", null);
                }
            }
            case RUNNING -> {
                if (Exporter.active()) return;
                Path video = Exporter.lastVideo;
                end(mc, video == null ? Exporter.lastResult : null, video);
            }
        }
    }

    private void end(Minecraft mc, String problem, Path video) {
        current = null;
        if (pack != null) {
            try {
                dev.breeze.packs.PackMenu.apply(mc, before);
            } catch (Throwable t) {
                BreezeClient.LOGGER.warn("[Breeze] render: the packs could not be put back: {}", t.toString());
            }
        }
        if (video == null) {
            lastResult = problem == null ? "The render made no video." : problem;
            BreezeClient.LOGGER.info("[Breeze] render: {}", lastResult);
            dev.breeze.shots.ScreenshotChat.say(mc, lastResult);
            return;
        }
        Thread t = new Thread(() -> {
            String soundProblem = carry(video);
            mc.execute(() -> {
                lastVideo = video;
                lastResult = "Rendered " + video.getFileName() + (soundProblem == null ? "" : " (without sound: " + soundProblem + ")");
                BreezeClient.LOGGER.info("[Breeze] render: {}", lastResult);
                if (soundProblem != null) dev.breeze.shots.ScreenshotChat.say(mc, "The render was saved without sound: " + soundProblem);
                dev.breeze.compat.ActiveScreen.set(mc, new ExportScreen(null, video, recording));
            });
        }, "Breeze render sound");
        t.setDaemon(true);
        t.start();
    }

    /** The screen video's tracks onto the render, in time; null when done or there was none. */
    private String carry(Path render) {
        if (soundFrom == null) return null;
        String ff = Ffmpeg.find();
        if (ff == null) return null;
        VideoProbe src = VideoProbe.of(ff, soundFrom);
        if (src == null || src.tracks().isEmpty() || src.startUs() == 0) return null;
        VideoProbe out = VideoProbe.of(ff, render);
        try {
            long startedUs = RecReader.readMeta(recording).startedAt * 1000L;
            return SoundMux.carry(ff, render, soundFrom, src.tracks(), startedUs, src.startUs(), out == null ? 0 : out.seconds());
        } catch (Exception e) {
            return e.getMessage();
        }
    }
}
