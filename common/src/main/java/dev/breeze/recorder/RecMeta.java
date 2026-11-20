package dev.breeze.recorder;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * What a recording says about itself, stored as JSON in its header: enough to
 * list it (launcher and game) and to refuse playing it on a Minecraft that
 * could not read its packets.
 */
public final class RecMeta {

    /** The Minecraft version it was recorded on, e.g. "1.21.11". Playback needs the same. */
    public String minecraft = "";
    /** The network protocol number of that version (a second check). */
    public int protocol;
    /** fabric, forge or neoforge. */
    public String loader = "";
    public String breeze = "";
    /** The world or server it was recorded in, for the list (a server's address is never stored). */
    public String world = "";
    /** Wall clock start, ms since 1970. */
    public long startedAt;
    /** Length in ms; 0 while recording (or if it was cut short and never finished). */
    public long durationMs;
    /** Whether the recording ended normally. */
    public boolean finished;
    /** The player's name, for the camera that follows them. */
    public String player = "";

    public String toJson() {
        return new Gson().toJson(this);
    }

    public static RecMeta fromJson(String json) {
        RecMeta m = new Gson().fromJson(json, RecMeta.class);
        return m == null ? new RecMeta() : m;
    }

    /** The same fields as a tree, for code that adds to it (the launcher's list). */
    public JsonObject toTree() {
        return new Gson().fromJson(toJson(), JsonObject.class);
    }
}
