package dev.breeze.recorder;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The recording's sound, made by the Breeze launcher, which started the
 * game and keeps running while it plays: on Windows 10 2004 and later it
 * records only this game's own sound (process loopback of the game's
 * process tree, so not Discord or music players) and the chosen microphone,
 * each into its own WAV file.
 *
 * They talk through files in the game folder's {@code recordings/.audio/},
 * the way the launcher already fetches ffmpeg when the game asks: no port is
 * opened. The launcher writes {@code status.json} every two seconds while
 * the game runs (whether sound can be recorded here, why not, and the
 * microphones); the game writes {@code start.json} and {@code stop.json}
 * naming a recording; the launcher writes {@code <id>.json} with each
 * track's file and the wall-clock moment of its first sample, so the tracks
 * are put in step with the video when they are joined.
 */
public final class AudioLink {

    private static final Gson GSON = new Gson();
    /** status.json older than this means no launcher is listening. */
    static final long STALE_MS = 10_000;

    private AudioLink() {}

    public record Device(String id, String name) {}

    /** What the launcher says it can do. */
    public record Status(boolean launcher, boolean game, boolean mic, String reason, List<Device> devices) {
        /** True when at least one kind of sound can be recorded. */
        public boolean any() {
            return launcher && (game || mic);
        }
    }

    /** One track the launcher recorded: its file and when its first sample was heard (ms since 1970, fractional). */
    public record Track(Path file, double startEpochMs) {}

    /** The launcher's answer for a recording once it stopped. */
    public record Result(Track game, Track mic, String problem) {}

    public static Path dir(Path gameDir) {
        return gameDir.resolve("recordings").resolve(".audio");
    }

    public static Status status(Path gameDir) {
        return status(gameDir, System.currentTimeMillis());
    }

    static Status status(Path gameDir, long now) {
        JsonObject o = read(dir(gameDir).resolve("status.json"));
        if (o == null || now - num(o, "updated", 0) > STALE_MS) {
            return new Status(false, false, false, "Sound is recorded by the Breeze launcher: start Minecraft from it.", List.of());
        }
        List<Device> devices = new ArrayList<>();
        JsonElement d = o.get("devices");
        if (d != null && d.isJsonArray()) {
            for (JsonElement e : (JsonArray) d) {
                if (!e.isJsonObject()) continue;
                JsonObject x = e.getAsJsonObject();
                devices.add(new Device(str(x, "id"), str(x, "name")));
            }
        }
        return new Status(true, bool(o, "game"), bool(o, "mic"), str(o, "reason"), devices);
    }

    /** Asks the launcher to record; returns false when the request could not be written. */
    public static boolean start(Path gameDir, String id, boolean game, boolean mic, String micDevice) {
        if (!safeId(id)) return false;
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("game", game);
        o.addProperty("mic", mic);
        o.addProperty("micDevice", micDevice == null ? "" : micDevice);
        o.addProperty("pid", ProcessHandle.current().pid());
        o.addProperty("requested", System.currentTimeMillis());
        try {
            Files.deleteIfExists(dir(gameDir).resolve(id + ".json"));
        } catch (Exception ignored) {}
        return write(dir(gameDir).resolve("start.json"), o);
    }

    public static boolean stop(Path gameDir, String id) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("requested", System.currentTimeMillis());
        return write(dir(gameDir).resolve("stop.json"), o);
    }

    /** The launcher's state for a recording, or null while it has not written one. */
    public static JsonObject state(Path gameDir, String id) {
        return safeId(id) ? read(dir(gameDir).resolve(id + ".json")) : null;
    }

    /**
     * Waits (off the render thread) until the launcher says the recording's
     * tracks are finished, at most timeoutMs. Tracks that are missing or
     * empty are null.
     */
    public static Result await(Path gameDir, String id, long timeoutMs) {
        long until = System.currentTimeMillis() + timeoutMs;
        JsonObject o = null;
        while (System.currentTimeMillis() < until) {
            o = state(gameDir, id);
            if (o != null && bool(o, "done")) break;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (o == null) return new Result(null, null, "the launcher did not record sound");
        Path rec = gameDir.resolve("recordings");
        Track game = track(rec, o, "game"), mic = track(rec, o, "mic");
        String problem = str(o, "error");
        if (!bool(o, "done") && problem.isEmpty()) problem = "the launcher did not finish the sound in time";
        return new Result(game, mic, problem.isEmpty() ? null : problem);
    }

    private static Track track(Path rec, JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonObject()) return null;
        JsonObject t = e.getAsJsonObject();
        String name = str(t, "file");
        // Only a file name in the recordings folder's .audio, never a path.
        if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")) return null;
        Path f = rec.resolve(".audio").resolve(name);
        try {
            if (!Files.isRegularFile(f) || Files.size(f) <= 44) return null;
        } catch (Exception ex) {
            return null;
        }
        return new Track(f, num(t, "startEpochMs", 0));
    }

    static boolean safeId(String id) {
        return id != null && !id.isEmpty() && id.length() <= 120 && id.matches("[A-Za-z0-9._-]+") && !id.contains("..");
    }

    private static boolean write(Path f, JsonObject o) {
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(o), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (Exception e) {
            try {
                Files.writeString(f, GSON.toJson(o), StandardCharsets.UTF_8);
                return true;
            } catch (Exception e2) {
                return false;
            }
        }
    }

    static JsonObject read(Path f) {
        try {
            if (!Files.isRegularFile(f)) return null;
            JsonElement e = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), JsonElement.class);
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? "" : e.getAsString();
    }

    private static boolean bool(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    private static double num(JsonObject o, String k, double fallback) {
        JsonElement e = o.get(k);
        try {
            return e == null || !e.isJsonPrimitive() ? fallback : e.getAsDouble();
        } catch (Exception ex) {
            return fallback;
        }
    }
}
