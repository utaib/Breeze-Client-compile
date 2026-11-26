package dev.breeze.recorder;

import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;

/**
 * Where Breeze's recorder meets the game: every packet the client receives
 * (RecConnectionMixin), every client tick, and starting and stopping. This
 * is the form for versions the recorder does not reach yet: it records
 * nothing and says so, and the Recorder module is not offered there.
 * Versions with the recorder carry their own copy (versions/shared/recorder-*).
 */
public final class RecorderHooks {

    private RecorderHooks() {}

    /** Whether this build can record and play back. */
    public static boolean supported() {
        return false;
    }

    public static void onPacket(Connection connection, Packet<?> packet) {}

    public static boolean copying() {
        return false;
    }

    public static String lastCopy() {
        return "";
    }

    public static String lastWatchProblem() {
        return null;
    }

    public static void tick(Minecraft mc) {}

    public static boolean recording() {
        return false;
    }

    /** How long the running recording is, in ms; -1 when none runs. */
    public static long recordedMs() {
        return -1;
    }

    /** Starts recording; null when it started, else why not (for chat). */
    public static String start(Minecraft mc) {
        return "Recording is not available on this Minecraft version yet.";
    }

    public static boolean sessionActive() {
        return false;
    }

    public static String startSession(Minecraft mc) {
        return start(mc);
    }

    public static String stopSession(Minecraft mc) {
        return null;
    }

    /** Stops recording; the saved file's name, or null when nothing was recording. */
    public static String stop(Minecraft mc) {
        return null;
    }

    /** Opens a recording to watch; null when it opened, else why not. */
    public static String watch(Minecraft mc, java.nio.file.Path file) {
        return "Recordings cannot be watched on this Minecraft version yet.";
    }

    /** What is playing (self-test and the playback bar): null when nothing plays. */
    public static String replayInfo() {
        return null;
    }

    /** What recording has cost off the render thread so far (self-test), or empty. */
    public static String stats() {
        return "";
    }

    /** Starts exporting a recording to a video; null when it started, else why not. */
    public static String export(Minecraft mc, java.nio.file.Path file, int fps, VideoEncoder.Quality quality) {
        return "Recordings cannot be exported on this Minecraft version yet.";
    }

    /** How the last export ended ("Video saved: ..." or the problem); null while none has. */
    public static String lastExport() {
        return null;
    }

    /** The last video made, or null. */
    public static java.nio.file.Path lastVideo() {
        return null;
    }

    /** Playback controls (the Playback bar): toggle, back, forward, slower, faster, leave. */
    public static void control(Minecraft mc, String what) {}

    /** The recording being watched, or null. */
    public static java.nio.file.Path watching() {
        return null;
    }
}
