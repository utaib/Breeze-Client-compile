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
 * The Spotify app on the player's own computer, without Spotify's Web API.
 *
 * Since February 2026 Spotify lets an app in Development Mode serve only five
 * accounts its owner adds by hand, and Extended Quota is for companies with
 * 250,000 monthly users, so most players can never use Breeze's Spotify
 * login (owner, 2026-11-08: keep the login for those five, and control the
 * local app for everyone). This reads what the app on this computer plays and
 * sends it play/pause, next and previous, the way the keyboard's media keys
 * and the system's media controls do. It works with Free and Premium alike.
 *
 * Per system:
 * - macOS: Spotify's own AppleScript (osascript): state, track, album,
 *   artwork address, position, length. macOS asks the player once whether
 *   Minecraft may control Spotify.
 * - Linux: MPRIS on the session bus (dbus-send), which Spotify publishes as
 *   org.mpris.MediaPlayer2.spotify, Flatpak and Snap builds included.
 * - Windows: the Spotify window's title ("Artist - Song" while playing,
 *   "Spotify Premium" or "Spotify Free" while paused) and the app commands
 *   Windows' media keys send, posted to that window ({@link LocalSpotifyWindows},
 *   through JNA, which Minecraft ships). No artwork, position or length there.
 *
 * Every call blocks for up to two seconds (a child process or a window
 * walk), so callers run it off the game thread.
 */
public final class LocalSpotify {

    private LocalSpotify() {}

    public enum Command { PLAY_PAUSE, NEXT, PREVIOUS }

    /**
     * What the local app shows. {@code present} is false when Spotify is not
     * running here; the other fields are null, or -1 for numbers, when this
     * system does not tell.
     */
    public static final class Reading {
        public final boolean present;
        public final Boolean playing;
        public final String title;
        public final String artist;
        public final String album;
        public final String artworkUrl;
        public final long positionMs;
        public final long durationMs;

        public Reading(boolean present, Boolean playing, String title, String artist, String album,
                       String artworkUrl, long positionMs, long durationMs) {
            this.present = present;
            this.playing = playing;
            this.title = blank(title) ? null : title;
            this.artist = blank(artist) ? null : artist;
            this.album = blank(album) ? null : album;
            this.artworkUrl = artwork(artworkUrl);
            this.positionMs = positionMs;
            this.durationMs = durationMs;
        }

        static final Reading ABSENT = new Reading(false, null, null, null, null, null, -1, -1);

        public boolean hasTrack() {
            return present && title != null;
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

    /** Whether this system has a way to reach a local Spotify at all. */
    public static boolean supported() {
        return OS != Os.OTHER;
    }

    /** The local app's state now. Never throws; ABSENT when it cannot tell. */
    public static Reading read() {
        try {
            switch (OS) {
                case MAC: return readMac();
                case LINUX: return readLinux();
                case WINDOWS: return LocalSpotifyWindows.read();
                default: return Reading.ABSENT;
            }
        } catch (Throwable t) {
            return Reading.ABSENT;
        }
    }

    /** Sends a command to the local app; false when there was none to send it to. */
    public static boolean command(Command c) {
        try {
            switch (OS) {
                case MAC: return commandMac(c);
                case LINUX: return commandLinux(c);
                case WINDOWS: return LocalSpotifyWindows.command(c);
                default: return false;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    // ── macOS ───────────────────────────────────────────────────────────────

    // The running check needs no dictionary, so it never asks where Spotify
    // is when it is not installed; the tell block only runs when it is.
    private static final String MAC_RUNNING = "application id \"com.spotify.client\" is running";
    private static final String MAC_READ = String.join("\n",
            "tell application id \"com.spotify.client\"",
            "set st to player state as string",
            "if st is \"stopped\" then return \"stopped\"",
            "set t to current track",
            "return st & linefeed & (name of t) & linefeed & (artist of t) & linefeed & (album of t) & linefeed & "
                    + "(artwork url of t) & linefeed & ((player position * 1000) as integer) & linefeed & (duration of t)",
            "end tell");

    private static Reading readMac() {
        String running = run(2, "osascript", "-e", MAC_RUNNING);
        if (running == null || !running.trim().equals("true")) return Reading.ABSENT;
        String out = run(2, "osascript", "-e", MAC_READ);
        return out == null ? new Reading(true, null, null, null, null, null, -1, -1) : parseMac(out);
    }

    private static boolean commandMac(Command c) {
        String running = run(2, "osascript", "-e", MAC_RUNNING);
        if (running == null || !running.trim().equals("true")) return false;
        String verb = switch (c) {
            case PLAY_PAUSE -> "playpause";
            case NEXT -> "next track";
            case PREVIOUS -> "previous track";
        };
        return run(2, "osascript", "-e", "tell application id \"com.spotify.client\" to " + verb) != null;
    }

    /** osascript's answer to MAC_READ. Package-private for the tests. */
    static Reading parseMac(String out) {
        String[] l = out.replace("\r", "").split("\n", -1);
        String state = l[0].trim();
        if (state.equals("stopped") || l.length < 7) return new Reading(true, false, null, null, null, null, -1, -1);
        return new Reading(true, state.equals("playing"), l[1], l[2], l[3], l[4].trim(), number(l[5]), number(l[6]));
    }

    // ── Linux (MPRIS) ───────────────────────────────────────────────────────

    private static final String MPRIS = "org.mpris.MediaPlayer2.spotify";

    private static String[] mprisGet(String property) {
        return new String[]{"dbus-send", "--print-reply", "--dest=" + MPRIS, "/org/mpris/MediaPlayer2",
                "org.freedesktop.DBus.Properties.Get", "string:org.mpris.MediaPlayer2.Player", "string:" + property};
    }

    private static Reading readLinux() {
        String status = run(2, mprisGet("PlaybackStatus"));
        if (status == null) return Reading.ABSENT;
        String meta = run(2, mprisGet("Metadata"));
        String position = run(2, mprisGet("Position"));
        return parseLinux(status, meta, position);
    }

    private static boolean commandLinux(Command c) {
        String method = switch (c) {
            case PLAY_PAUSE -> "PlayPause";
            case NEXT -> "Next";
            case PREVIOUS -> "Previous";
        };
        return run(2, "dbus-send", "--print-reply", "--dest=" + MPRIS, "/org/mpris/MediaPlayer2",
                "org.mpris.MediaPlayer2.Player." + method) != null;
    }

    private static final Pattern DBUS_STRING = Pattern.compile("^\\s*(?:variant\\s+)?string \"(.*)\"\\s*$");
    private static final Pattern DBUS_NUMBER = Pattern.compile("^\\s*(?:variant\\s+)?(?:u?int(?:16|32|64)|double)\\s+(-?[0-9.]+)\\s*$");

    /** dbus-send's answers for PlaybackStatus, Metadata and Position. Package-private for the tests. */
    static Reading parseLinux(String status, String meta, String position) {
        Boolean playing = null;
        for (String line : lines(status)) {
            Matcher m = DBUS_STRING.matcher(line);
            if (m.matches()) playing = m.group(1).equals("Playing");
        }
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
        // Spotify on Linux answers Position 0 for the whole song; that is
        // unknown, not the start.
        if (pos == 0 && Boolean.TRUE.equals(playing)) pos = -1;
        return new Reading(true, playing, title, artists.isEmpty() ? null : String.join(", ", artists), album, art, pos, length);
    }

    // ── shared ──────────────────────────────────────────────────────────────

    /** "Artist - Song", or Spotify's idle titles. Package-private for the tests and the Windows half. */
    static Reading parseWindowTitle(String title) {
        if (title == null) return Reading.ABSENT;
        String t = title.trim();
        if (t.isEmpty()) return Reading.ABSENT;
        if (t.equals("Spotify") || t.equals("Spotify Premium") || t.equals("Spotify Free")) {
            return new Reading(true, false, null, null, null, null, -1, -1);
        }
        int dash = t.indexOf(" - ");
        if (dash <= 0) return new Reading(true, true, t, null, null, null, -1, -1);
        return new Reading(true, true, t.substring(dash + 3), t.substring(0, dash), null, null, -1, -1);
    }

    /**
     * Only Spotify's own image hosts, as the Web API path draws them
     * (SpotifyArt checks the same); MPRIS used to give open.spotify.com
     * addresses for the same images.
     */
    static String artwork(String url) {
        if (url == null) return null;
        String u = url.trim();
        if (u.startsWith("https://open.spotify.com/image/")) u = "https://i.scdn.co/image/" + u.substring("https://open.spotify.com/image/".length());
        if (u.startsWith("http://i.scdn.co/")) u = "https://" + u.substring("http://".length());
        return u.startsWith("https://i.scdn.co/image/") ? u : null;
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
