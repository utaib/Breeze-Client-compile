package dev.breeze.recorder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The sound of one screen video: the game's own sound (recorded by the Breeze
 * launcher, {@link SoundLink}) and the microphone ({@link MicSource}), each
 * into its own file while recording, joined with the video when it stops
 * ({@link SoundMux}) as two named tracks, Game and Microphone, so either can
 * be muted later. Starting and stopping never wait on the game's thread for
 * more than opening the microphone.
 */
public final class SoundSession {

    /** The self-test's generated sources instead of the microphone (the build machines have none). */
    public static volatile boolean testTones;

    private static final String GAME = "game", MIC = "mic";

    private final Path gameDir;
    private final String name;
    private final SoundClock clock = new SoundClock();
    private boolean gameAsked;
    private MicSource mic;
    private ToneSource tone;
    private final List<String> notes = new ArrayList<>();
    private String summary = "";

    private SoundSession(Path gameDir, String name) {
        this.gameDir = gameDir;
        this.name = name;
    }

    /**
     * Starts the sound the preferences ask for, for the screen video named
     * {@code name}. Null when neither is asked for. What could not start is
     * kept as a note ({@link #notes}); the video records anyway.
     */
    public static SoundSession start(Path gameDir, String name, boolean game, boolean micOn, String micDevice, long pid) {
        if (!game && !micOn) return null;
        SoundSession s = new SoundSession(gameDir, name);
        if (game) {
            SoundLink.Ready r = SoundLink.ready(gameDir, pid);
            if (!r.available()) {
                s.notes.add("Game sound: " + r.reason());
            } else {
                try {
                    Files.deleteIfExists(SoundLink.info(gameDir, name, GAME));
                    SoundLink.askStart(gameDir, name);
                    s.gameAsked = true;
                } catch (Exception e) {
                    s.notes.add("Game sound: the launcher could not be asked (" + e.getMessage() + ").");
                }
            }
        }
        if (micOn) {
            try {
                Path pcm = SoundLink.pcm(gameDir, name, MIC), info = SoundLink.info(gameDir, name, MIC);
                if (testTones) s.tone = new ToneSource(pcm, info, 1000, s.clock::nowUs, "Breeze test microphone");
                else s.mic = MicSource.start(micDevice, pcm, info, s.clock);
            } catch (Throwable t) {
                s.notes.add("Microphone: " + (t.getMessage() == null ? "it could not be opened" : t.getMessage()) + ".");
            }
        }
        return s;
    }

    /** The clock the video's frames are stamped with, so they line up with the sound. */
    public SoundClock clock() {
        return clock;
    }

    public String name() {
        return name;
    }

    /** What could not be recorded, in words for chat. */
    public List<String> notes() {
        return notes;
    }

    /** Stops every source (the microphone at once; the launcher is asked). */
    public void stop() {
        if (mic != null) mic.stop();
        if (tone != null) tone.stop();
        if (gameAsked) {
            try {
                SoundLink.askStop(gameDir, name);
            } catch (Exception e) {
                notes.add("Game sound: the launcher could not be asked to stop.");
            }
        }
    }

    /**
     * Adds the tracks to the finished video (after {@link #stop}): waits a few
     * seconds for the launcher to close the game's track, then joins. Null
     * when there was nothing to add or it worked, else what went wrong.
     */
    public String mux(String ffmpeg, Path video, long videoStartUs, double durationSec) {
        List<SoundMux.Input> in = new ArrayList<>();
        if (gameAsked) {
            SoundTrack.Info gi = waitDone(SoundLink.info(gameDir, name, GAME), 8_000);
            if (gi == null) notes.add("Game sound: the launcher did not record it (an older launcher, or it was closed).");
            else if (gi.failed()) notes.add("Game sound: " + (gi.error() == null ? "the launcher could not record it." : gi.error()));
            else in.add(new SoundMux.Input(SoundLink.pcm(gameDir, name, GAME), gi, "Game"));
        }
        if (mic != null || tone != null) {
            SoundTrack.Info mi = SoundTrack.Info.read(SoundLink.info(gameDir, name, MIC));
            if (mi != null) in.add(new SoundMux.Input(SoundLink.pcm(gameDir, name, MIC), mi, "Microphone"));
            if (mic != null && mic.failure() != null) notes.add("Microphone: it stopped (" + mic.failure() + ").");
        }
        StringBuilder sb = new StringBuilder();
        for (SoundMux.Input i : in) {
            sb.append(i.title()).append('{').append(i.info()).append(String.format(Locale.ROOT, " offsetMs=%.1f", (i.info().startUs() - videoStartUs) / 1000.0)).append("} ");
        }
        summary = "tracks=" + in.size() + " " + sb.toString().trim();
        if (in.isEmpty()) {
            cleanup();
            return null;
        }
        String err = SoundMux.run(ffmpeg, video, in, videoStartUs, durationSec);
        if (err == null) cleanup();
        return err;
    }

    /** Stops and throws the tracks away (the video was never made). */
    public void discard() {
        stop();
        waitDone(SoundLink.info(gameDir, name, GAME), gameAsked ? 3_000 : 0);
        cleanup();
    }

    /** The tracks joined and how far each started from the first frame (log and self-test). */
    public String summary() {
        return summary;
    }

    private static SoundTrack.Info waitDone(Path info, long ms) {
        long until = System.currentTimeMillis() + ms;
        SoundTrack.Info i = null;
        while (System.currentTimeMillis() < until) {
            i = SoundTrack.Info.read(info);
            if (i != null && (i.done() || i.failed())) return i;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // Still "recording" (the launcher went away): what is in the file is used.
        return i;
    }

    private void cleanup() {
        for (String k : new String[]{GAME, MIC}) {
            try {
                Files.deleteIfExists(SoundLink.pcm(gameDir, name, k));
                Files.deleteIfExists(SoundLink.info(gameDir, name, k));
            } catch (Exception ignored) {}
        }
    }
}
