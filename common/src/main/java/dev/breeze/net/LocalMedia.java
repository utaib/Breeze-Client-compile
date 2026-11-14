package dev.breeze.net;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The music playing on the player's own computer, whatever plays it:
 * Spotify, YouTube Music in a browser, Apple Music and other players.
 *
 * Why: Spotify lets an app in Development Mode serve only five accounts its
 * owner adds, so most players cannot use Breeze's Spotify login (owner,
 * 2026-11-08: control the local app for everyone), and players asked for
 * YouTube Music too (owner, 2026-11-12). This reads what the computer plays
 * and sends play/pause, next and previous, the way the keyboard's media keys
 * and the system's media controls do. No account is needed.
 *
 * Per system:
 * - Windows: the system's media sessions (the same list as the volume
 *   flyout), read by a small PowerShell helper that stays running while
 *   music is shown ({@link LocalMediaWindows}): any player that tells Windows
 *   what it plays, browsers included. Title, artist, album, cover, position.
 *   If the helper cannot start, the Spotify window's title as before.
 * - Linux: MPRIS on the session bus (dbus-send): every player that
 *   publishes org.mpris.MediaPlayer2.*, browsers included.
 * - macOS: Spotify and Apple Music through their AppleScript (osascript).
 *   macOS asks the player once whether Minecraft may control each. A
 *   browser's music cannot be read there.
 *
 * With several players, the one playing wins, and the one used last is kept
 * while it plays.
 *
 * Every call blocks for up to a few seconds (a child process), so callers
 * run it off the game thread.
 */
public final class LocalMedia {

    private LocalMedia() {}

    public enum Command { PLAY_PAUSE, NEXT, PREVIOUS }

    /** Marks an artwork value that is not an address: {@link #artworkBytes} fetches it. */
    public static final String LOCAL_ART = "local:";

    /**
     * What the computer plays. {@code present} is false when no player is
     * running here; the other fields are null, or -1 for numbers, when this
     * system does not tell.
     */
    public static final class Reading {
        public final boolean present;
        public final Boolean playing;
        public final String title;
        public final String artist;
        public final String album;
        /** An https address on a known image host, or a {@link #LOCAL_ART} key, or null. */
        public final String artworkUrl;
        public final long positionMs;
        public final long durationMs;
        /** The player's name as the system gives it ("Spotify", "Chrome"), or null. */
        public final String app;

        public Reading(boolean present, Boolean playing, String title, String artist, String album,
                       String artworkUrl, long positionMs, long durationMs) {
            this(present, playing, title, artist, album, artworkUrl, positionMs, durationMs, null);
        }

        public Reading(boolean present, Boolean playing, String title, String artist, String album,
                       String artworkUrl, long positionMs, long durationMs, String app) {
            this.present = present;
            this.playing = playing;
            this.title = blank(title) ? null : clean(title, 200);
            this.artist = blank(artist) ? null : clean(artist, 200);
            this.album = blank(album) ? null : clean(album, 200);
            this.artworkUrl = artwork(artworkUrl);
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.app = blank(app) ? null : clean(app, 40);
        }

        static final Reading ABSENT = new Reading(false, null, null, null, null, null, -1, -1, null);

        public boolean hasTrack() {
            return present && title != null;
        }

        Reading withApp(String app) {
            return new Reading(present, playing, title, artist, album, artworkUrl, positionMs, durationMs, app);
        }
    }

    enum Os { MAC, LINUX, WINDOWS, OTHER }

    static Os os(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.contains("mac") || n.contains("darwin")) return Os.MAC;
        if (n.contains("win")) return Os.WINDOWS;
        if (n.contains("nux") || n.contains("nix") || n.contains("bsd")) return Os.LINUX;
        return Os.OTHER;
    }

    private static final Os OS = os(System.getProperty("os.name"));

    /** Whether this system has a way to reach a local player at all. */
    public static boolean supported() {
        return OS != Os.OTHER;
    }

    /** The local app's state now. Never throws; ABSENT when it cannot tell. */
    public static Reading read() {
        try {
            switch (OS) {
                case MAC: return readMac();
                case LINUX: return readLinux();
                case WINDOWS: return LocalMediaWindows.read();
                default: return Reading.ABSENT;
            }
        } catch (Throwable t) {
            return Reading.ABSENT;
        }
    }

    /** Sends a command to the player last read; false when there was none to send it to. */
    public static boolean command(Command c) {
        try {
            switch (OS) {
                case MAC: return commandMac(c);
                case LINUX: return commandLinux(c);
                case WINDOWS: return LocalMediaWindows.command(c);
                default: return false;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    // ── macOS ───────────────────────────────────────────────────────────────

    /** Spotify, then Apple Music: {id, name, whether its track length is in seconds}. */
    private static final String[][] MAC_APPS = {
            {"com.spotify.client", "Spotify", "ms"},
            {"com.apple.Music", "Apple Music", "s"},
    };
    private static volatile int macLast;

    // The running check needs no dictionary, so it never asks where an app
    // is when it is not installed; the tell block only runs when it is.
    private static String macRunning(String id) {
        return "application id \"" + id + "\" is running";
    }

    private static String macRead(String[] app) {
        boolean spotify = app[2].equals("ms");
        return String.join("\n",
                "tell application id \"" + app[0] + "\"",
                "set st to player state as string",
                "if st is \"stopped\" then return \"stopped\"",
                "set t to current track",
                "return st & linefeed & (name of t) & linefeed & (artist of t) & linefeed & (album of t) & linefeed & "
                        + (spotify ? "(artwork url of t)" : "\"\"") + " & linefeed & ((player position * 1000) as integer) & linefeed & "
                        + (spotify ? "(duration of t)" : "(((duration of t) * 1000) as integer)"),
                "end tell");
    }

    private static boolean macIsRunning(String[] app) {
        String running = run(2, "osascript", "-e", macRunning(app[0]));
        return running != null && running.trim().equals("true");
    }

    private static Reading macReadApp(String[] app) {
        String out = run(2, "osascript", "-e", macRead(app));
        Reading r = out == null ? new Reading(true, null, null, null, null, null, -1, -1) : parseMac(out);
        return r.withApp(app[1]);
    }

    private static Reading readMac() {
        // The app used last first; another one only when that one is not playing.
        int first = macLast;
        Reading kept = null;
        for (int n = 0; n < MAC_APPS.length; n++) {
            int i = (first + n) % MAC_APPS.length;
            if (!macIsRunning(MAC_APPS[i])) continue;
            Reading r = macReadApp(MAC_APPS[i]);
            if (Boolean.TRUE.equals(r.playing)) {
                macLast = i;
                return r;
            }
            if (kept == null) {
                kept = r;
                macLast = i;
            }
        }
        return kept == null ? Reading.ABSENT : kept;
    }

    private static boolean commandMac(Command c) {
        String[] app = MAC_APPS[macLast];
        if (!macIsRunning(app)) return false;
        String verb = switch (c) {
            case PLAY_PAUSE -> "playpause";
            case NEXT -> "next track";
            case PREVIOUS -> "previous track";
        };
        return run(2, "osascript", "-e", "tell application id \"" + app[0] + "\" to " + verb) != null;
    }

    /** osascript's answer to {@link #macRead}. Package-private for the tests. */
    static Reading parseMac(String out) {
        String[] l = out.replace("\r", "").split("\n", -1);
        String state = l[0].trim();
        if (state.equals("stopped") || l.length < 7) return new Reading(true, false, null, null, null, null, -1, -1);
        return new Reading(true, state.equals("playing"), l[1], l[2], l[3], l[4].trim(), number(l[5]), number(l[6]));
    }

    // ── Linux (MPRIS) ───────────────────────────────────────────────────────

    private static final String MPRIS_PREFIX = "org.mpris.MediaPlayer2.";
    /** Enough for a desktop; more would mean a child process each, every reading. */
    private static final int MAX_PLAYERS = 6;
    private static volatile String linuxLast;
    private static final java.util.Map<String, String> IDENTITY = new java.util.concurrent.ConcurrentHashMap<>();

    private static String[] mprisGet(String player, String iface, String property) {
        return new String[]{"dbus-send", "--print-reply", "--dest=" + player, "/org/mpris/MediaPlayer2",
                "org.freedesktop.DBus.Properties.Get", "string:" + iface, "string:" + property};
    }

    /** The MPRIS players in dbus-send's ListNames answer. Package-private for the tests. */
    static List<String> parseNames(String out) {
        List<String> names = new ArrayList<>();
        for (String line : lines(out)) {
            Matcher m = DBUS_STRING.matcher(line);
            if (!m.matches()) continue;
            String name = m.group(1);
            // playerctld repeats whichever player is active; it is not one itself.
            if (!name.startsWith(MPRIS_PREFIX) || name.endsWith(".playerctld")) continue;
            if (!name.matches("[A-Za-z0-9_.\\-]{1,200}")) continue;
            if (names.size() < MAX_PLAYERS) names.add(name);
        }
        return names;
    }

    /**
     * Which player to show: the one used last while it plays, else the first
     * one playing, else the one used last, else the first. Package-private
     * for the tests.
     */
    static String pick(List<String> names, java.util.Map<String, Boolean> playing, String last) {
        if (names.isEmpty()) return null;
        if (last != null && names.contains(last) && Boolean.TRUE.equals(playing.get(last))) return last;
        for (String n : names) if (Boolean.TRUE.equals(playing.get(n))) return n;
        if (last != null && names.contains(last)) return last;
        return names.get(0);
    }

    /** A player's name for the screen: what it calls itself, else from its bus name. Package-private for the tests. */
    static String appName(String identity, String busName) {
        if (!blank(identity)) return identity.trim();
        if (busName == null || !busName.startsWith(MPRIS_PREFIX)) return null;
        String rest = busName.substring(MPRIS_PREFIX.length());
        int dot = rest.indexOf('.');
        if (dot > 0) rest = rest.substring(0, dot);
        if (rest.isEmpty()) return null;
        return rest.length() <= 3 ? rest.toUpperCase(Locale.ROOT) : Character.toUpperCase(rest.charAt(0)) + rest.substring(1);
    }

    private static Boolean playingOf(String statusOut) {
        Boolean playing = null;
        for (String line : lines(statusOut)) {
            Matcher m = DBUS_STRING.matcher(line);
            if (m.matches()) playing = m.group(1).equals("Playing");
        }
        return playing;
    }

    private static Reading readLinux() {
        String listed = run(2, "dbus-send", "--session", "--print-reply", "--dest=org.freedesktop.DBus",
                "/org/freedesktop/DBus", "org.freedesktop.DBus.ListNames");
        List<String> names = parseNames(listed);
        if (names.isEmpty()) return Reading.ABSENT;
        java.util.Map<String, Boolean> playing = new java.util.HashMap<>();
        java.util.Map<String, String> statuses = new java.util.HashMap<>();
        for (String n : names) {
            String status = run(2, mprisGet(n, "org.mpris.MediaPlayer2.Player", "PlaybackStatus"));
            if (status == null) continue;
            statuses.put(n, status);
            playing.put(n, playingOf(status));
        }
        names.removeIf(n -> !statuses.containsKey(n));
        String player = pick(names, playing, linuxLast);
        if (player == null) return Reading.ABSENT;
        linuxLast = player;
        String meta = run(2, mprisGet(player, "org.mpris.MediaPlayer2.Player", "Metadata"));
        String position = run(2, mprisGet(player, "org.mpris.MediaPlayer2.Player", "Position"));
        String identity = IDENTITY.computeIfAbsent(player, p -> {
            String out = run(2, mprisGet(p, "org.mpris.MediaPlayer2", "Identity"));
            for (String line : lines(out)) {
                Matcher m = DBUS_STRING.matcher(line);
                if (m.matches()) return m.group(1);
            }
            return "";
        });
        if (IDENTITY.size() > 32) IDENTITY.clear();
        boolean spotify = player.equals(MPRIS_PREFIX + "spotify");
        return parseLinux(statuses.get(player), meta, position, spotify).withApp(appName(identity, player));
    }

    private static boolean commandLinux(Command c) {
        String player = linuxLast;
        if (player == null) return false;
        String method = switch (c) {
            case PLAY_PAUSE -> "PlayPause";
            case NEXT -> "Next";
            case PREVIOUS -> "Previous";
        };
        return run(2, "dbus-send", "--print-reply", "--dest=" + player, "/org/mpris/MediaPlayer2",
                "org.mpris.MediaPlayer2.Player." + method) != null;
    }

    private static final Pattern DBUS_STRING = Pattern.compile("^\\s*(?:variant\\s+)?string \"(.*)\"\\s*$");
    private static final Pattern DBUS_NUMBER = Pattern.compile("^\\s*(?:variant\\s+)?(?:u?int(?:16|32|64)|double)\\s+(-?[0-9.]+)\\s*$");

    /**
     * dbus-send's answers for PlaybackStatus, Metadata and Position.
     * {@code zeroUnknown}: the Spotify app answers Position 0 for the whole
     * song, which is unknown, not the start. Package-private for the tests.
     */
    static Reading parseLinux(String status, String meta, String position, boolean zeroUnknown) {
        Boolean playing = playingOf(status);
        String title = null, album = null, art = null;
        List<String> artists = new ArrayList<>();
        long length = -1;
        String key = null;
        boolean inArtists = false;
        for (String line : lines(meta)) {
            String t = line.trim();
            if (t.startsWith("dict entry(")) {
                key = null;
                inArtists = false;
                continue;
            }
            Matcher s = DBUS_STRING.matcher(line);
            Matcher n = DBUS_NUMBER.matcher(line);
            if (key == null) {
                if (s.matches() && !t.startsWith("variant")) key = s.group(1);
                continue;
            }
            if (t.startsWith("variant") && t.contains("array [")) {
                inArtists = key.equals("xesam:artist");
                continue;
            }
            if (s.matches()) {
                String v = s.group(1);
                switch (key) {
                    case "xesam:title" -> title = v;
                    case "xesam:album" -> album = v;
                    case "mpris:artUrl" -> art = v;
                    case "xesam:artist" -> { if (inArtists || t.startsWith("variant")) artists.add(v); }
                    default -> { }
                }
            } else if (n.matches() && key.equals("mpris:length")) {
                length = (long) (Double.parseDouble(n.group(1)) / 1000);
            }
        }
        long pos = -1;
        for (String line : lines(position)) {
            Matcher n = DBUS_NUMBER.matcher(line);
            if (n.matches()) pos = (long) (Double.parseDouble(n.group(1)) / 1000);
        }
        if (zeroUnknown && pos == 0 && Boolean.TRUE.equals(playing)) pos = -1;
        return new Reading(true, playing, title, artists.isEmpty() ? null : String.join(", ", artists), album, art, pos, length);
    }

    // ── shared ──────────────────────────────────────────────────────────────

    /** The Spotify window's title: "Artist - Song", or its idle titles. Package-private for the tests and the Windows half. */
    static Reading parseWindowTitle(String title) {
        if (title == null) return Reading.ABSENT;
        String t = title.trim();
        if (t.isEmpty()) return Reading.ABSENT;
        if (t.equals("Spotify") || t.equals("Spotify Premium") || t.equals("Spotify Free")) {
            return new Reading(true, false, null, null, null, null, -1, -1, "Spotify");
        }
        int dash = t.indexOf(" - ");
        if (dash <= 0) return new Reading(true, true, t, null, null, null, -1, -1, "Spotify");
        return new Reading(true, true, t.substring(dash + 3), t.substring(0, dash), null, null, -1, -1, "Spotify");
    }

    /** Image hosts a cover may come from: Spotify's, and YouTube Music's. */
    private static final java.util.Set<String> ART_HOSTS = java.util.Set.of("i.scdn.co", "lh3.googleusercontent.com", "i.ytimg.com");

    /** Whether a cover address is on one of the hosts {@link #artwork} lets through. */
    public static boolean artHost(String host) {
        return host != null && ART_HOSTS.contains(host.toLowerCase(Locale.ROOT));
    }

    /**
     * A cover the game may show: an https address on a known image host
     * (MPRIS used to give open.spotify.com addresses for Spotify's images),
     * a picture file a local player wrote (browsers do), kept as a
     * {@link #LOCAL_ART} key, or such a key itself. Anything else is null.
     */
    static String artwork(String url) {
        if (url == null) return null;
        String u = url.trim();
        if (u.startsWith(LOCAL_ART)) return u.length() <= 1024 ? u : null;
        if (u.startsWith("file:///")) {
            try {
                String path = java.nio.file.Paths.get(java.net.URI.create(u)).toString();
                return path.length() <= 1000 ? LOCAL_ART + "file:" + path : null;
            } catch (RuntimeException e) {
                return null;
            }
        }
        if (u.startsWith("https://open.spotify.com/image/")) u = "https://i.scdn.co/image/" + u.substring("https://open.spotify.com/image/".length());
        if (u.startsWith("http://i.scdn.co/")) u = "https://" + u.substring("http://".length());
        try {
            java.net.URI parsed = java.net.URI.create(u);
            return "https".equals(parsed.getScheme()) && artHost(parsed.getHost()) && u.length() <= 1024 ? u : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Covers larger than this are not read. */
    static final int MAX_ART_BYTES = 2_000_000;

    /**
     * The picture behind a {@link #LOCAL_ART} key (an image file a player
     * wrote, or the cover Windows holds for the song), or null. Blocks; call
     * off the game thread.
     */
    public static byte[] artworkBytes(String key) {
        try {
            if (key == null || !key.startsWith(LOCAL_ART)) return null;
            String rest = key.substring(LOCAL_ART.length());
            if (rest.startsWith("file:")) {
                java.nio.file.Path p = java.nio.file.Paths.get(rest.substring("file:".length()));
                if (!java.nio.file.Files.isRegularFile(p) || java.nio.file.Files.size(p) > MAX_ART_BYTES) return null;
                return java.nio.file.Files.readAllBytes(p);
            }
            if (rest.startsWith("smtc:") && OS == Os.WINDOWS) return LocalMediaWindows.artwork(key);
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Text from a player, safe to draw: no control or formatting characters, one line, at most max long. */
    static String clean(String s, int max) {
        StringBuilder b = new StringBuilder(Math.min(s.length(), max));
        boolean space = false;
        for (int i = 0; i < s.length() && b.length() < max; i++) {
            char c = s.charAt(i);
            if (c == '\u00a7') {
                i++;
                continue;
            }
            if (Character.isISOControl(c) || Character.isWhitespace(c)) {
                space = b.length() > 0;
                continue;
            }
            if (space) b.append(' ');
            space = false;
            b.append(c);
        }
        return b.toString();
    }

    private static List<String> lines(String s) {
        return s == null ? List.of() : List.of(s.replace("\r", "").split("\n"));
    }

    private static long number(String s) {
        try {
            return (long) Double.parseDouble(s.trim().replace(',', '.'));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank() || s.equals("missing value");
    }

    /** A child process's output, or null when it failed, timed out or could not start. */
    static String run(int seconds, String... command) {
        Process p = null;
        try {
            p = new ProcessBuilder(command).redirectErrorStream(false).start();
            p.getOutputStream().close();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream in = p.getInputStream();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            byte[] buf = new byte[4096];
            while (true) {
                while (in.available() > 0) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    out.write(buf, 0, Math.min(n, Math.max(0, 65_536 - out.size())));
                }
                if (!p.isAlive()) {
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, Math.min(n, Math.max(0, 65_536 - out.size())));
                    break;
                }
                if (System.nanoTime() > deadline) return null;
                Thread.sleep(15);
            }
            return p.exitValue() == 0 ? out.toString(StandardCharsets.UTF_8) : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
        }
    }
}
