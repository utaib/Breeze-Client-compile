package dev.breeze.shots;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShotsTest {

    @TempDir
    Path dir;

    private Path pic(String name, long modified) throws Exception {
        Path p = dir.resolve(name);
        Files.write(p, new byte[]{1, 2, 3});
        Files.setLastModifiedTime(p, FileTime.fromMillis(modified));
        return p;
    }

    @Test
    void listsPicturesNewestFirst() throws Exception {
        pic("2026-11-19_10.00.00.png", 1_000);
        pic("2026-11-19_10.00.05.png", 5_000);
        pic("old.jpg", 500);
        Files.writeString(dir.resolve("notes.txt"), "x");
        Files.createDirectory(dir.resolve("sub.png"));
        List<ShotFiles.Shot> l = ShotFiles.list(dir);
        assertEquals(3, l.size());
        assertEquals("2026-11-19_10.00.05.png", l.get(0).name());
        assertEquals("old.jpg", l.get(2).name());
        assertTrue(ShotFiles.list(dir.resolve("missing")).isEmpty());
    }

    @Test
    void namesNeverLeaveTheFolder() throws Exception {
        pic("a.png", 1);
        assertNotNull(ShotFiles.resolve(dir, "a.png"));
        assertNull(ShotFiles.resolve(dir, "../a.png"));
        assertNull(ShotFiles.resolve(dir, "sub/a.png"));
        assertNull(ShotFiles.resolve(dir, "..\\a.png"));
        assertNull(ShotFiles.resolve(dir, "missing.png"));
        assertNull(ShotFiles.resolve(dir, ".hidden.png"));
        assertNull(ShotFiles.resolve(dir, "a.txt"));
        assertFalse(ShotFiles.safeName("C:a.png"));
        assertFalse(ShotFiles.safeName("a\n.png"));
        assertTrue(ShotFiles.safeName("2026-11-19_10.00.00_1.png"));
    }

    @Test
    void findsThePictureAMessageNames() throws Exception {
        pic("2026-11-19_10.00.00.png", 10_000);
        pic("2026-11-19_10.00.01.png", 11_000);
        // Translated text around the name, as Minecraft writes it in any language.
        ShotFiles.Shot s = ShotFiles.named(dir, "Captura guardada como 2026-11-19_10.00.00.png", 9_000);
        assertNotNull(s);
        assertEquals("2026-11-19_10.00.00.png", s.name());
        // Written before the screenshot was asked for: not this one.
        assertNull(ShotFiles.named(dir, "Saved screenshot as 2026-11-19_10.00.00.png", 12_000));
        // A failed screenshot names no file.
        assertNull(ShotFiles.named(dir, "Couldn't save screenshot: disk full", 0));
        // The name read straight from the text, with Minecraft's _1 suffix and a full stop after it.
        pic("2026-11-19_10.00.01_1.png", 11_500);
        ShotFiles.Shot t = ShotFiles.named(dir, "Saved screenshot as 2026-11-19_10.00.01_1.png.", 11_000);
        assertNotNull(t);
        assertEquals("2026-11-19_10.00.01_1.png", t.name());
    }

    @Test
    void linksRoundTripAndRejectOthers() {
        String s = ShotLinks.encode(ShotLinks.Action.COPY, "a b.png");
        assertEquals("breeze:shot:copy:a b.png", s);
        ShotLinks.Link l = ShotLinks.decode(s);
        assertEquals(ShotLinks.Action.COPY, l.action());
        assertEquals("a b.png", l.name());
        assertNull(ShotLinks.decode("breeze:shot:delete:a.png"));
        assertNull(ShotLinks.decode("breeze:shot:view:../x.png"));
        assertNull(ShotLinks.decode("hello"));
        assertNull(ShotLinks.decode(null));
        assertEquals(ShotLinks.Action.WATCH, ShotLinks.decode("breeze:shot:watch:2026-11-19_10.00.00.breezerec").action());
        assertNull(ShotLinks.decode("breeze:shot:watch:../a.breezerec"));
        assertNull(ShotLinks.decode("breeze:shot:export:a.png"));
        assertNull(ShotLinks.decode("breeze:shot:view:a.breezerec"));
    }

    @Test
    void systemNames() {
        assertEquals(SystemOpen.Os.WINDOWS, SystemOpen.os("Windows 11"));
        assertEquals(SystemOpen.Os.MAC, SystemOpen.os("Mac OS X"));
        assertEquals(SystemOpen.Os.LINUX, SystemOpen.os("Linux"));
        assertEquals(SystemOpen.Os.LINUX, SystemOpen.os("FreeBSD"));
    }

    @Test
    void revealPerSystem() {
        Path f = dir.resolve("my shot,1.png");
        List<SystemOpen.Cmd> w = SystemOpen.revealCommands(SystemOpen.Os.WINDOWS, f);
        assertEquals("explorer.exe", w.get(0).args().get(0));
        assertEquals("/select," + f.toAbsolutePath(), w.get(0).args().get(1));
        assertFalse(w.get(0).exitCodeMatters());
        List<SystemOpen.Cmd> m = SystemOpen.revealCommands(SystemOpen.Os.MAC, f);
        assertEquals(List.of("open", "-R", f.toAbsolutePath().toString()), m.get(0).args());
        List<SystemOpen.Cmd> l = SystemOpen.revealCommands(SystemOpen.Os.LINUX, f);
        assertEquals("dbus-send", l.get(0).args().get(0));
        String arr = l.get(0).args().get(7);
        assertTrue(arr.startsWith("array:string:file:///"), arr);
        assertFalse(arr.substring("array:string:".length()).contains(","), "a comma would split the array");
        assertEquals("xdg-open", l.get(l.size() - 1).args().get(0));
        assertEquals(dir.toAbsolutePath().toString(), l.get(l.size() - 1).args().get(1));
    }

    @Test
    void copyPerSystem() {
        Path f = dir.resolve("shot.png");
        SystemOpen.Cmd win = SystemOpen.copyCommands(SystemOpen.Os.WINDOWS, Map.of(), f).get(0);
        assertEquals("powershell.exe", win.args().get(0));
        assertTrue(win.args().contains("-STA"));
        // The path reaches PowerShell only through the environment.
        assertFalse(String.join(" ", win.args()).contains("shot.png"));
        assertEquals(f.toAbsolutePath().toString(), win.env().get("BREEZE_CLIP_FILE"));

        SystemOpen.Cmd mac = SystemOpen.copyCommands(SystemOpen.Os.MAC, Map.of(), f).get(0);
        assertEquals("osascript", mac.args().get(0));
        assertEquals(f.toAbsolutePath().toString(), mac.args().get(mac.args().size() - 1));
        assertTrue(String.join(" ", mac.args()).contains("PNGf"));

        List<SystemOpen.Cmd> wayland = SystemOpen.copyCommands(SystemOpen.Os.LINUX,
                Map.of("WAYLAND_DISPLAY", "wayland-0", "DISPLAY", ":0"), f);
        assertEquals("wl-copy", wayland.get(0).args().get(0));
        assertEquals(f.toAbsolutePath().toFile(), wayland.get(0).stdin());
        assertEquals("xclip", wayland.get(1).args().get(0));
        assertTrue(wayland.get(1).args().contains("image/png"));

        List<SystemOpen.Cmd> x11 = SystemOpen.copyCommands(SystemOpen.Os.LINUX, Map.of("DISPLAY", ":0"), f);
        assertEquals(1, x11.size());
        assertEquals("xclip", x11.get(0).args().get(0));

        assertTrue(SystemOpen.copyCommands(SystemOpen.Os.LINUX, Map.of(), f).isEmpty());
        assertTrue(SystemOpen.copyFailure(SystemOpen.Os.LINUX, Map.of("WAYLAND_DISPLAY", "w")).contains("wl-clipboard"));
        assertTrue(SystemOpen.copyFailure(SystemOpen.Os.LINUX, Map.of("DISPLAY", ":0")).contains("xclip"));
        assertEquals("image/jpeg", SystemOpen.mime(dir.resolve("a.JPG")));
    }

    @Test
    void sizesForPeople() {
        assertEquals("512 B", ShotFiles.size(512));
        assertEquals("1.5 KB", ShotFiles.size(1536));
        assertEquals("2.0 MB", ShotFiles.size(2 * 1024 * 1024));
    }
}
