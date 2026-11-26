package dev.breeze.shots;

import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * What the screenshot actions ask of the player's system: show a file in the
 * file manager, and put a picture on the clipboard. Each is a list of
 * programs to try in order (built by pure methods, unit tested), run on a
 * background thread with a time limit, never on the render thread.
 *
 * Minecraft may run with AWT headless (it does on macOS, and launchers set
 * it elsewhere), so the clipboard goes through the system's own tools first:
 * PowerShell on Windows, osascript on macOS, wl-copy on Wayland and xclip on
 * X11. AWT is only the last try, where it is not headless and not on macOS
 * (AWT next to GLFW's window there can stop the game). When nothing works,
 * the result says so in words a player can act on.
 */
public final class SystemOpen {

    private SystemOpen() {}

    public enum Os { WINDOWS, MAC, LINUX, OTHER }

    /** How a finished action went; message is a sentence for chat. */
    public record Result(boolean ok, String message) {}

    /** A program to run: its arguments, what it reads on standard input (or null), extra environment, and whether its exit code means anything. */
    public record Cmd(List<String> args, File stdin, Map<String, String> env, boolean exitCodeMatters) {
        static Cmd of(String... args) {
            return new Cmd(List.of(args), null, Map.of(), true);
        }
    }

    public static Os os(String osName) {
        String n = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (n.startsWith("windows")) return Os.WINDOWS;
        if (n.startsWith("mac") || n.contains("darwin")) return Os.MAC;
        if (n.contains("linux") || n.contains("bsd") || n.contains("nux")) return Os.LINUX;
        return Os.OTHER;
    }

    public static Os os() {
        return os(System.getProperty("os.name"));
    }

    // ── Show in folder ─────────────────────────────────────────────────────

    /** The programs that show file selected in its folder, best first, then ones that open the folder. */
    public static List<Cmd> revealCommands(Os os, Path file) {
        Path abs = file.toAbsolutePath().normalize();
        String folder = abs.getParent() == null ? abs.toString() : abs.getParent().toString();
        List<Cmd> out = new ArrayList<>();
        switch (os) {
            case WINDOWS -> {
                // Explorer answers 1 even when it worked, so its exit code is
                // ignored. Java quotes the argument as a whole because of the
                // path's spaces, which Explorer reads as /select,<path>.
                out.add(new Cmd(List.of("explorer.exe", "/select," + abs), null, Map.of(), false));
                out.add(new Cmd(List.of("explorer.exe", folder), null, Map.of(), false));
            }
            case MAC -> {
                out.add(Cmd.of("open", "-R", abs.toString()));
                out.add(Cmd.of("open", folder));
            }
            default -> {
                // The freedesktop file manager interface (Nautilus, Dolphin,
                // Nemo, Thunar, Caja and others): opens the folder with the
                // file selected. A comma would split dbus-send's array.
                String uri = abs.toUri().toASCIIString().replace(",", "%2C");
                out.add(Cmd.of("dbus-send", "--session", "--print-reply", "--dest=org.freedesktop.FileManager1",
                        "--type=method_call", "/org/freedesktop/FileManager1", "org.freedesktop.FileManager1.ShowItems",
                        "array:string:" + uri, "string:"));
                out.add(Cmd.of("gdbus", "call", "--session", "--dest", "org.freedesktop.FileManager1",
                        "--object-path", "/org/freedesktop/FileManager1", "--method", "org.freedesktop.FileManager1.ShowItems",
                        "['" + uri.replace("'", "%27") + "']", ""));
                out.add(Cmd.of("xdg-open", folder));
            }
        }
        return out;
    }

    /** Shows file in the system's file manager, off the calling thread; done hears how it went. */
    public static void reveal(Path file, Consumer<Result> done) {
        List<Cmd> cmds = revealCommands(os(), file);
        background("Breeze folder", () -> {
            for (Cmd c : cmds) {
                if (run(c, 8)) return new Result(true, "Opened the screenshots folder.");
            }
            return new Result(false, "The screenshots folder could not be opened on this system.");
        }, done);
    }

    /** The programs that open a folder in the file manager. */
    public static List<Cmd> openCommands(Os os, Path folder) {
        String f = folder.toAbsolutePath().normalize().toString();
        return switch (os) {
            case WINDOWS -> List.of(new Cmd(List.of("explorer.exe", f), null, Map.of(), false));
            case MAC -> List.of(Cmd.of("open", f));
            default -> List.of(Cmd.of("xdg-open", f));
        };
    }

    /** Opens a folder in the system's file manager, off the calling thread. */
    public static void openFolder(Path folder, Consumer<Result> done) {
        List<Cmd> cmds = openCommands(os(), folder);
        background("Breeze folder", () -> {
            for (Cmd c : cmds) {
                if (run(c, 8)) return new Result(true, "Opened the screenshots folder.");
            }
            return new Result(false, "The screenshots folder could not be opened on this system.");
        }, done);
    }

    /** Opens a file (a video) with the program the system plays it with, off the calling thread. */
    public static void openFile(Path file, Consumer<Result> done) {
        List<Cmd> cmds = openCommands(os(), file);
        background("Breeze open", () -> {
            for (Cmd c : cmds) {
                if (run(c, 8)) return new Result(true, "Opened " + file.getFileName() + ".");
            }
            return new Result(false, file.getFileName() + " could not be opened on this system: it is in " + file.toAbsolutePath().getParent() + ".");
        }, done);
    }

    // ── Copy a picture ─────────────────────────────────────────────────────

    static final String WINDOWS_SCRIPT = "try { Add-Type -AssemblyName System.Windows.Forms,System.Drawing; "
            + "$i = [System.Drawing.Image]::FromFile($env:BREEZE_CLIP_FILE); "
            + "[System.Windows.Forms.Clipboard]::SetImage($i); $i.Dispose(); exit 0 } catch { exit 1 }";

    static String mime(Path file) {
        String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") ? "image/jpeg" : "image/png";
    }

    /**
     * The programs that put the picture on the clipboard, in order. env is
     * the process environment (WAYLAND_DISPLAY and DISPLAY decide Linux's
     * tools). Empty when the system has none Breeze knows.
     */
    public static List<Cmd> copyCommands(Os os, Map<String, String> env, Path file) {
        Path abs = file.toAbsolutePath().normalize();
        List<Cmd> out = new ArrayList<>();
        switch (os) {
            case WINDOWS ->
                // The path goes in through the environment, never inside the
                // script text, so no file name can change what runs.
                    out.add(new Cmd(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-STA", "-Command", WINDOWS_SCRIPT),
                            null, Map.of("BREEZE_CLIP_FILE", abs.toString()), true));
            case MAC -> {
                String type = "image/jpeg".equals(mime(abs)) ? "JPEG picture" : "«class PNGf»";
                out.add(Cmd.of("osascript", "-e", "on run argv", "-e",
                        "set the clipboard to (read (POSIX file (item 1 of argv)) as " + type + ")", "-e", "end run",
                        abs.toString()));
            }
            case LINUX, OTHER -> {
                String mime = mime(abs);
                if (present(env, "WAYLAND_DISPLAY")) {
                    out.add(new Cmd(List.of("wl-copy", "--type", mime), abs.toFile(), Map.of(), true));
                }
                if (present(env, "DISPLAY")) {
                    out.add(Cmd.of("xclip", "-selection", "clipboard", "-t", mime, "-i", abs.toString()));
                }
            }
        }
        return out;
    }

    /** What chat says when no way to copy worked, per system. */
    public static String copyFailure(Os os, Map<String, String> env) {
        return switch (os) {
            case WINDOWS -> "Windows did not take the picture on the clipboard. Try Copy again, or use Folder.";
            case MAC -> "macOS did not take the picture on the clipboard. Try Copy again, or use Folder.";
            default -> present(env, "WAYLAND_DISPLAY")
                    ? "This system cannot take a picture on the clipboard: install wl-clipboard (wl-copy) and try again."
                    : present(env, "DISPLAY")
                    ? "This system cannot take a picture on the clipboard: install xclip and try again."
                    : "This system has no clipboard Breeze can reach.";
        };
    }

    /** Puts the picture on the clipboard, off the calling thread; done hears how it went. */
    public static void copyImage(Path file, Consumer<Result> done) {
        Os os = os();
        Map<String, String> env = System.getenv();
        List<Cmd> cmds = copyCommands(os, env, file);
        background("Breeze copy", () -> {
            for (Cmd c : cmds) {
                if (run(c, 15)) return new Result(true, "Copied " + file.getFileName() + " to the clipboard.");
            }
            if (os != Os.MAC && awtCopy(file)) {
                return new Result(true, "Copied " + file.getFileName() + " to the clipboard.");
            }
            return new Result(false, copyFailure(os, env));
        }, done);
    }

    /** AWT's clipboard, where AWT is not headless; false when it cannot. */
    static boolean awtCopy(Path file) {
        try {
            if (GraphicsEnvironment.isHeadless()) return false;
            Image img = javax.imageio.ImageIO.read(file.toFile());
            if (img == null) return false;
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new Picture(img), null);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private record Picture(Image image) implements Transferable {
        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.imageFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.imageFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) throw new UnsupportedFlavorException(flavor);
            return image;
        }
    }

    // ── Running ────────────────────────────────────────────────────────────

    private static boolean present(Map<String, String> env, String key) {
        String v = env == null ? null : env.get(key);
        return v != null && !v.isBlank();
    }

    /**
     * Runs one program and waits for it (xclip and wl-copy fork a helper that
     * keeps serving the clipboard; the program itself returns at once). Its
     * output goes nowhere, so a helper holding it open cannot stall Breeze.
     * False when it is missing, fails or overruns.
     */
    static boolean run(Cmd c, int seconds) {
        try {
            ProcessBuilder pb = new ProcessBuilder(c.args());
            pb.environment().putAll(c.env());
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            if (c.stdin() != null) pb.redirectInput(c.stdin());
            Process p = pb.start();
            if (!p.waitFor(seconds, TimeUnit.SECONDS)) {
                p.destroy();
                return false;
            }
            return !c.exitCodeMatters() || p.exitValue() == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private interface Job {
        Result run();
    }

    private static void background(String name, Job job, Consumer<Result> done) {
        Thread t = new Thread(() -> {
            Result r;
            try {
                r = job.run();
            } catch (Throwable e) {
                r = new Result(false, "That did not work on this system.");
            }
            if (done != null) done.accept(r);
        }, name);
        t.setDaemon(true);
        t.start();
    }
}
