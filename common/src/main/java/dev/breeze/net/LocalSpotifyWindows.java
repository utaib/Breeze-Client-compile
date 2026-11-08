package dev.breeze.net;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;

import java.util.Locale;

/**
 * The Windows half of {@link LocalSpotify}: finds Spotify's main window by
 * its process (Spotify.exe), reads its title, and posts it the app commands
 * Windows' media keys produce (WM_APPCOMMAND), so the command reaches
 * Spotify even when another app holds the system's media session.
 *
 * JNA comes with Minecraft on every version Breeze supports; this class is
 * only loaded on Windows, and {@link LocalSpotify} catches anything it throws.
 */
final class LocalSpotifyWindows {

    private LocalSpotifyWindows() {}

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
            boolean spotify = spotifyPids.computeIfAbsent(pid.getValue(), LocalSpotifyWindows::isSpotify);
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

    static LocalSpotify.Reading read() {
        Object[] w = find();
        return w == null ? LocalSpotify.Reading.ABSENT : LocalSpotify.parseWindowTitle((String) w[1]);
    }

    static boolean command(LocalSpotify.Command c) {
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
