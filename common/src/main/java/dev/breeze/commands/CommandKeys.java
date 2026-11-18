package dev.breeze.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Command keys: a key (with Ctrl, Shift or Alt if the player wants) that
 * runs a Minecraft command. This is the part with no Minecraft in it: what
 * a binding is, what a command may contain, and the saved file. The game
 * side (versions/, commands/CommandKeyRunner) decides whether a command is
 * available and sends it the way Minecraft sends a typed command, so the
 * server's permissions apply as always.
 *
 * Keys are kept by Minecraft's own key name ("key.keyboard.k"), not by a
 * number: 26.3 numbers keys differently (SDL) from every version before.
 */
public final class CommandKeys {

    /** Most bindings kept; a file with more keeps the first ones. */
    public static final int MAX_BINDINGS = 50;
    /** Minecraft's limit for a typed command, without the slash. */
    public static final int MAX_COMMAND = 255;
    private static final int MAX_NAME = 40;

    private CommandKeys() {}

    /**
     * One binding. {@code key} is a Minecraft key name or "" for none yet;
     * the modifiers must be held with it (and only those: Ctrl+K does not
     * fire on Ctrl+Shift+K).
     */
    public record Binding(String id, String command, String key, boolean ctrl, boolean shift, boolean alt, boolean enabled) {

        public Binding withCommand(String c) { return new Binding(id, c, key, ctrl, shift, alt, enabled); }

        public Binding withKey(String k, boolean c, boolean s, boolean a) { return new Binding(id, command, k, c, s, a, enabled); }

        public Binding withEnabled(boolean e) { return new Binding(id, command, key, ctrl, shift, alt, e); }

        public boolean bound() { return key != null && !key.isEmpty(); }

        public boolean hasModifier() { return ctrl || shift || alt; }

        /** The same key and the same modifiers. */
        public boolean sameChord(Binding o) {
            return bound() && o.bound() && key.equals(o.key) && ctrl == o.ctrl && shift == o.shift && alt == o.alt;
        }
    }

    /**
     * The command to keep, without its slash, or an IllegalArgumentException
     * with what to change. One line, no formatting codes and no control
     * characters: what Minecraft itself allows in a typed command (a server
     * disconnects a player who sends a formatting code).
     */
    public static String cleanCommand(String raw) {
        if (raw == null) throw new IllegalArgumentException("Type a command.");
        String c = raw.strip();
        while (c.startsWith("/")) c = c.substring(1);
        c = c.strip();
        if (c.isEmpty()) throw new IllegalArgumentException("Type a command.");
        for (int i = 0; i < c.length(); i++) {
            char ch = c.charAt(i);
            if (ch == '§') throw new IllegalArgumentException("Formatting codes (§) are not allowed in a command.");
            if (ch == '\n' || ch == '\r') throw new IllegalArgumentException("A command is one line.");
            if (ch < ' ' || ch == 127) throw new IllegalArgumentException("A command cannot hold control characters.");
        }
        if (c.length() > MAX_COMMAND) throw new IllegalArgumentException("Use at most " + MAX_COMMAND + " characters.");
        return c;
    }

    /** The command's name: what it starts with, as Minecraft's command list knows it. */
    public static String name(String command) {
        String c = command == null ? "" : command.strip();
        while (c.startsWith("/")) c = c.substring(1);
        int sp = c.indexOf(' ');
        return sp < 0 ? c : c.substring(0, sp);
    }

    /** A Minecraft key name as Breeze accepts it: "key.keyboard.<name>", nothing else. */
    public static boolean keyName(String key) {
        return key != null && key.length() <= MAX_NAME && key.matches("key\\.keyboard\\.[a-z0-9_.]+");
    }

    /**
     * The saved list, read without trusting it: entries that are not
     * objects, have no id or a duplicate id, or whose command or key the
     * rules refuse are left out (a refused key keeps the binding, unbound).
     */
    public static List<Binding> parse(String json) {
        List<Binding> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (Throwable t) {
            return out;
        }
        JsonArray arr = root.isJsonObject() && root.getAsJsonObject().has("bindings") && root.getAsJsonObject().get("bindings").isJsonArray()
                ? root.getAsJsonObject().getAsJsonArray("bindings") : null;
        if (arr == null) return out;
        Set<String> ids = new HashSet<>();
        for (JsonElement e : arr) {
            if (out.size() >= MAX_BINDINGS) break;
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            String id = str(o, "id");
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,24}") || !ids.add(id)) continue;
            String command;
            try {
                command = cleanCommand(str(o, "command"));
            } catch (IllegalArgumentException bad) {
                continue;
            }
            String key = str(o, "key");
            boolean ok = keyName(key);
            out.add(new Binding(id, command, ok ? key : "", ok && bool(o, "ctrl"), ok && bool(o, "shift"), ok && bool(o, "alt"),
                    !o.has("enabled") || bool(o, "enabled")));
        }
        return out;
    }

    public static String write(List<Binding> bindings) {
        JsonArray arr = new JsonArray();
        for (Binding b : bindings) arr.add(json(b));
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("bindings", arr);
        return root.toString();
    }

    public static JsonObject json(Binding b) {
        JsonObject o = new JsonObject();
        o.addProperty("id", b.id());
        o.addProperty("command", b.command());
        o.addProperty("key", b.key() == null ? "" : b.key());
        o.addProperty("ctrl", b.ctrl());
        o.addProperty("shift", b.shift());
        o.addProperty("alt", b.alt());
        o.addProperty("enabled", b.enabled());
        return o;
    }

    /** A new id not in use: "c1", "c2", ... */
    public static String newId(List<Binding> bindings) {
        Set<String> used = new HashSet<>();
        for (Binding b : bindings) used.add(b.id());
        for (int i = 1; ; i++) {
            String id = "c" + i;
            if (!used.contains(id)) return id;
        }
    }

    /**
     * Whether a binding fires now: its key went down this moment (it was up
     * before), and exactly its modifiers are held. Firing on the press only
     * is what keeps one press from running a command again and again.
     */
    public static boolean fires(Binding b, boolean keyDownNow, boolean keyDownBefore, boolean ctrl, boolean shift, boolean alt) {
        return b.enabled() && b.bound() && keyDownNow && !keyDownBefore && b.ctrl() == ctrl && b.shift() == shift && b.alt() == alt;
    }

    private static String str(JsonObject o, String k) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean bool(JsonObject o, String k) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsBoolean();
        } catch (Throwable t) {
            return false;
        }
    }
}
