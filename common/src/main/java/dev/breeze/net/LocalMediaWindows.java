package dev.breeze.net;

import com.google.gson.JsonObject;
import com.google.gson.Gson;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The Windows half of {@link LocalMedia}.
 *
 * First choice: Windows' own media sessions (System Media Transport
 * Controls, the list the volume flyout shows). Any player that tells Windows
 * what it plays is there: Spotify, Chrome, Edge and Firefox (so YouTube
 * Music), Apple Music and others, with title, artist, album, cover and
 * position. Java cannot call that API itself, so a small PowerShell helper
 * (Windows PowerShell 5.1, part of Windows 10 and 11) is started once and
 * answers one-letter commands on its input: r read, a cover, p play/pause,
 * n next, b previous. Its script is passed as the command itself: no file is
 * written and no execution policy is changed. It ends when the game does,
 * or after two minutes with nothing asked.
 *
 * Fallback, when the helper cannot start (PowerShell missing or locked
 * down): Spotify's main window, found by its process (Spotify.exe): its
 * title, and the app commands Windows' media keys produce (WM_APPCOMMAND).
 *
 * JNA comes with Minecraft on every version Breeze supports; this class is
 * only loaded on Windows, and {@link LocalMedia} catches anything it throws.
 */
final class LocalMediaWindows {

    private LocalMediaWindows() {}

    // ── the media-session helper ────────────────────────────────────────────

    /**
     * The helper, one statement per entry. No double quote anywhere (it is
     * one command-line argument), and its answers are plain ASCII (other
     * characters as JSON escapes), so no encoding setting matters.
     */
    static final String SCRIPT = String.join("; ",
            "$ErrorActionPreference = 'Stop'",
            "Add-Type -AssemblyName System.Runtime.WindowsRuntime",
            "$asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' } | Select-Object -First 1",
            "function Await($op, $type) { $t = $asTask.MakeGenericMethod($type).Invoke($null, @($op)); if (-not $t.Wait(2500)) { throw 'timeout' }; $t.Result }",
            "$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]",
            "$null = [Windows.Storage.Streams.DataReader, Windows.Storage.Streams, ContentType = WindowsRuntime]",
            "$mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])",
            "$script:last = ''",
            "function Playing($s) { [string]$s.GetPlaybackInfo().PlaybackStatus -eq 'Playing' }",
            // The player used last while it plays, else one that plays, else the one used last, else Windows' current one.
            "function Pick { $cur = $mgr.GetCurrentSession(); $all = @($mgr.GetSessions()); $keep = $null; foreach ($s in $all) { if ($s.SourceAppUserModelId -eq $script:last) { $keep = $s } }; if ($keep -and (Playing $keep)) { return $keep }; if ($cur -and (Playing $cur)) { return $cur }; foreach ($s in $all) { if (Playing $s) { return $s } }; if ($keep) { return $keep }; $cur }",
            "function Props($s) { Await ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties]) }",
            "function Read1 { $s = Pick; if (-not $s) { return @{ present = $false } }; $script:last = [string]$s.SourceAppUserModelId; $p = Props $s; $t = $s.GetTimelineProperties(); @{ present = $true; app = [string]$s.SourceAppUserModelId; status = [string]$s.GetPlaybackInfo().PlaybackStatus; title = [string]$p.Title; artist = [string]$p.Artist; album = [string]$p.AlbumTitle; positionMs = [long]$t.Position.TotalMilliseconds; durationMs = [long]($t.EndTime - $t.StartTime).TotalMilliseconds; updated = [long]$t.LastUpdatedTime.ToUnixTimeMilliseconds(); now = [long][DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds(); art = [bool]$p.Thumbnail } }",
            "function Art1 { $s = Pick; if (-not $s) { return '' }; $p = Props $s; if (-not $p.Thumbnail) { return '' }; $st = Await ($p.Thumbnail.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType]); if ($st.Size -gt 2000000) { return '' }; $n = [uint32]$st.Size; $r = New-Object Windows.Storage.Streams.DataReader($st.GetInputStreamAt(0)); $null = Await ($r.LoadAsync($n)) ([uint32]); $b = New-Object byte[] $n; $r.ReadBytes($b); [Convert]::ToBase64String($b) }",
            "function Send1($c) { $s = Pick; if (-not $s) { return 'no' }; if ($c -eq 'p') { $o = $s.TryTogglePlayPauseAsync() } elseif ($c -eq 'n') { $o = $s.TrySkipNextAsync() } else { $o = $s.TrySkipPreviousAsync() }; if (Await $o ([bool])) { 'ok' } else { 'no' } }",
            "function Ascii($x) { [regex]::Replace([string]$x, '[^\\x20-\\x7e]', { param($m) '\\u{0:x4}' -f [int][char]$m.Value }) }",
            "$stdin = New-Object System.IO.StreamReader([Console]::OpenStandardInput())",
            "[Console]::Out.WriteLine('ready')",
            "[Console]::Out.Flush()",
            // Ends when the game closes the pipe, says q, or asks nothing for two minutes.
            "while ($true) { $line = $stdin.ReadLineAsync(); if (-not $line.Wait(120000)) { break }; $l = $line.Result; if ($null -eq $l -or $l -eq 'q') { break }; try { if ($l -eq 'r') { $o = Ascii (Read1 | ConvertTo-Json -Compress) } elseif ($l -eq 'a') { $o = 'art ' + (Art1) } elseif ($l -eq 'p' -or $l -eq 'n' -or $l -eq 'b') { $o = Send1 $l } else { $o = 'no' } } catch { $o = 'err ' + (Ascii ($_.Exception.Message -replace '\\s+', ' ')) }; [Console]::Out.WriteLine($o); [Console]::Out.Flush() }");

    private static final Object HELPER_LOCK = new Object();
    private static Process helper;
    private static OutputStream helperIn;
    private static LinkedBlockingQueue<String> helperOut;
    private static int helperFailures;
    private static long helperRetryAt;
    /** The song the helper read last (app, title, artist, album), for the cover's key. */
    private static volatile String lastSong;

    /** Whether the helper is up, starting it when it is due. Call with HELPER_LOCK held. */
    private static boolean helperUp() {
        if (helper != null && helper.isAlive()) return true;
        stopHelper();
        long now = System.currentTimeMillis();
        if (helperFailures >= 3 || now < helperRetryAt) return false;
        try {
            String root = System.getenv("SystemRoot");
            if (root == null || root.isBlank()) root = "C:\\Windows";
            // By full path, so nothing else named powershell on the PATH is run.
            String exe = root + "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe";
            Process p = new ProcessBuilder(exe, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", SCRIPT)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.US_ASCII))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (lines.size() < 16) lines.add(line);
                    }
                } catch (Exception ignored) {
                }
            }, "Breeze media helper");
            reader.setDaemon(true);
            reader.start();
            helper = p;
            helperIn = p.getOutputStream();
            helperOut = lines;
            // PowerShell's first start can take seconds on a slow disk.
            String first = lines.poll(12, TimeUnit.SECONDS);
            if (!"ready".equals(first == null ? null : first.trim().replace("\uFEFF", ""))) throw new IllegalStateException("no helper");
            helperFailures = 0;
            return true;
        } catch (Throwable t) {
            stopHelper();
            helperFailures++;
            helperRetryAt = now + 60_000;
            return false;
        }
    }

    private static void stopHelper() {
        Process p = helper;
        helper = null;
        helperIn = null;
        helperOut = null;
        if (p != null && p.isAlive()) p.destroyForcibly();
    }

    /** One command to the helper and its one-line answer, or null (the helper is then restarted later). */
    private static String ask(String command, int seconds) {
        synchronized (HELPER_LOCK) {
            if (!helperUp()) return null;
            try {
                helperOut.clear();
                helperIn.write((command + "\n").getBytes(StandardCharsets.US_ASCII));
                helperIn.flush();
                String line = helperOut.poll(seconds, TimeUnit.SECONDS);
                if (line == null) throw new IllegalStateException("no answer");
                return line.trim();
            } catch (Throwable t) {
                // It may have ended on its own after two idle minutes: one
                // quiet restart is normal, so that is not counted against it.
                boolean ended = helper != null && !helper.isAlive();
                stopHelper();
                if (!ended) {
                    helperFailures++;
                    helperRetryAt = System.currentTimeMillis() + 60_000;
                }
                return null;
            }
        }
    }

    /** The helper's raw answer to a reading, or null when it is not up: for the test on a real Windows. */
    static String helperAnswer() {
        return ask("r", 6);
    }

    /** The helper's answer to r. Package-private for the tests. */
    static LocalMedia.Reading parseSession(String json) {
        // fromJson, not JsonParser.parseString: Minecraft 1.17's Gson has no such method.
        JsonObject o = new Gson().fromJson(json, JsonObject.class);
        if (o == null) return LocalMedia.Reading.ABSENT;
        if (!o.has("present") || !o.get("present").getAsBoolean()) return LocalMedia.Reading.ABSENT;
        String status = text(o, "status");
        boolean playing = "Playing".equals(status);
        long duration = number(o, "durationMs");
        long position = number(o, "positionMs");
        long updated = number(o, "updated");
        long now = number(o, "now");
        if (duration <= 0) {
            duration = -1;
            position = -1;
        } else {
            // Windows gives the position as of its last update; players
            // update it on play, pause and seek, not while a song runs.
            if (playing && updated > 0 && now >= updated) position += now - updated;
            // A player that never updates it would run past the end: unknown then.
            if (position < 0 || position > duration + 2_000) position = -1;
            else position = Math.min(position, duration);
        }
        String app = text(o, "app");
        String title = text(o, "title");
        String artist = text(o, "artist");
        String album = text(o, "album");
        boolean art = o.has("art") && o.get("art").getAsBoolean();
        String song = app + "\n" + title + "\n" + artist + "\n" + album;
        String key = art && title != null ? LocalMedia.LOCAL_ART + "smtc:" + Integer.toHexString(song.hashCode()) : null;
        return new LocalMedia.Reading(true, playing, title, artist, album, key, position, duration, appName(app));
    }

    /**
     * A player's name from the id Windows knows it by: "Spotify.exe",
     * "Chrome", "MSEdge", "SpotifyAB.SpotifyMusic_zpdnekdrzrea0!Spotify",
     * "AppleInc.AppleMusicWin_nzyj5cx40ttqa!App". Package-private for the tests.
     */
    static String appName(String id) {
        if (id == null || id.isBlank()) return null;
        String s = id.trim();
        int bang = s.lastIndexOf('!');
        String app = bang >= 0 ? s.substring(bang + 1) : s;
        if (bang >= 0 && (app.isEmpty() || app.equalsIgnoreCase("App"))) {
            // A Store app whose entry is just "App": the package's own name.
            String pkg = s.substring(0, bang);
            int under = pkg.indexOf('_');
            if (under > 0) pkg = pkg.substring(0, under);
            app = pkg.substring(pkg.lastIndexOf('.') + 1);
        }
        if (app.toLowerCase(Locale.ROOT).endsWith(".exe")) app = app.substring(0, app.length() - 4);
        String lower = app.toLowerCase(Locale.ROOT);
        if (lower.equals("msedge")) return "Edge";
        if (lower.equals("chrome")) return "Chrome";
        // Firefox registers under a hash of its install folder.
        if (lower.equals("firefox") || app.matches("[0-9A-F]{16}")) return "Firefox";
        if (lower.startsWith("applemusic")) return "Apple Music";
        if (lower.contains("spotify")) return "Spotify";
        if (!app.matches("[A-Za-z0-9 ._\\-]{1,40}")) return null;
        return Character.toUpperCase(app.charAt(0)) + app.substring(1);
    }

    private static String text(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    private static long number(JsonObject o, String k) {
        try {
            return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsLong() : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** The cover Windows holds for the song the key was made for, or null. */
    static byte[] artwork(String key) {
        String answer = ask("a", 4);
        if (answer == null || !answer.startsWith("art ")) return null;
        String b64 = answer.substring(4).trim();
        if (b64.isEmpty() || b64.length() > LocalMedia.MAX_ART_BYTES * 4 / 3 + 8) return null;
        byte[] bytes = java.util.Base64.getDecoder().decode(b64);
        // The song may have changed between the reading and now: then this
        // cover belongs to the next one, which asks under its own key.
        LocalMedia.Reading again = readSession();
        return again != null && key.equals(again.artworkUrl) ? bytes : null;
    }

    private static LocalMedia.Reading readSession() {
        String answer = ask("r", 4);
        if (answer == null || !answer.startsWith("{")) return null;
        try {
            return parseSession(answer);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ── the Spotify window (fallback) ───────────────────────────────────────

    interface User32 extends StdCallLibrary {
        interface EnumProc extends StdCallCallback {
            boolean callback(Pointer hWnd, Pointer data);
        }

        boolean EnumWindows(EnumProc proc, Pointer data);

        int GetWindowTextW(Pointer hWnd, char[] text, int max);

        int GetWindowThreadProcessId(Pointer hWnd, IntByReference pid);

        boolean PostMessageW(Pointer hWnd, int msg, Pointer wParam, Pointer lParam);
    }

    interface Kernel32 extends StdCallLibrary {
        Pointer OpenProcess(int access, boolean inherit, int pid);

        boolean QueryFullProcessImageNameW(Pointer process, int flags, char[] name, IntByReference size);

        boolean CloseHandle(Pointer handle);
    }

    private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
    private static final int WM_APPCOMMAND = 0x0319;
    private static final int APPCOMMAND_MEDIA_NEXTTRACK = 11;
    private static final int APPCOMMAND_MEDIA_PREVIOUSTRACK = 12;
    private static final int APPCOMMAND_MEDIA_PLAY_PAUSE = 14;

    private static User32 user32;
    private static Kernel32 kernel32;

    @SuppressWarnings("deprecation")
    private static synchronized void load() {
        if (user32 == null) {
            // loadLibrary exists in JNA 4 and 5 alike (Minecraft 1.17 to 26.x).
            user32 = (User32) Native.loadLibrary("user32", User32.class);
            kernel32 = (Kernel32) Native.loadLibrary("kernel32", Kernel32.class);
        }
    }

    /** Spotify's main window and its title, or null. */
    private static Object[] find() {
        load();
        Object[] found = new Object[2];
        java.util.Map<Integer, Boolean> spotifyPids = new java.util.HashMap<>();
        char[] buf = new char[512];
        user32.EnumWindows((hWnd, data) -> {
            int n = user32.GetWindowTextW(hWnd, buf, buf.length);
            if (n <= 0) return true;
            String title = new String(buf, 0, n);
            if (ignored(title)) return true;
            IntByReference pid = new IntByReference();
            user32.GetWindowThreadProcessId(hWnd, pid);
            boolean spotify = spotifyPids.computeIfAbsent(pid.getValue(), LocalMediaWindows::isSpotify);
            if (!spotify) return true;
            // Prefer a title that names a song; Spotify's idle titles otherwise.
            if (found[0] == null || (title.contains(" - ") && !((String) found[1]).contains(" - "))) {
                found[0] = hWnd;
                found[1] = title;
            }
            return true;
        }, null);
        return found[0] == null ? null : found;
    }

    /** Hidden helper windows every process has; not the player. */
    private static boolean ignored(String title) {
        return title.equals("Default IME") || title.equals("MSCTFIME UI") || title.startsWith("GDI+ ")
                || title.startsWith(".NET-BroadcastEventWindow");
    }

    private static boolean isSpotify(int pid) {
        Pointer h = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid);
        if (h == null) return false;
        try {
            char[] name = new char[1024];
            IntByReference size = new IntByReference(name.length);
            if (!kernel32.QueryFullProcessImageNameW(h, 0, name, size)) return false;
            String path = new String(name, 0, size.getValue()).toLowerCase(Locale.ROOT);
            return path.endsWith("\\spotify.exe");
        } finally {
            kernel32.CloseHandle(h);
        }
    }

    /** Whether the last reading came from the media sessions (commands then go there too). */
    private static volatile boolean viaSessions;

    static LocalMedia.Reading read() {
        LocalMedia.Reading r = readSession();
        if (r != null) {
            viaSessions = true;
            return r;
        }
        viaSessions = false;
        Object[] w = find();
        return w == null ? LocalMedia.Reading.ABSENT : LocalMedia.parseWindowTitle((String) w[1]);
    }

    static boolean command(LocalMedia.Command c) {
        if (viaSessions) {
            String answer = ask(switch (c) {
                case PLAY_PAUSE -> "p";
                case NEXT -> "n";
                case PREVIOUS -> "b";
            }, 4);
            if ("ok".equals(answer)) return true;
            // "no": the player refused, or it is gone. Spotify's window may still take it.
        }
        Object[] w = find();
        if (w == null) return false;
        int cmd = switch (c) {
            case PLAY_PAUSE -> APPCOMMAND_MEDIA_PLAY_PAUSE;
            case NEXT -> APPCOMMAND_MEDIA_NEXTTRACK;
            case PREVIOUS -> APPCOMMAND_MEDIA_PREVIOUSTRACK;
        };
        Pointer hWnd = (Pointer) w[0];
        return user32.PostMessageW(hWnd, WM_APPCOMMAND, hWnd, new Pointer(((long) cmd) << 16));
    }
}
