package dev.breeze.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Which menu wallpaper comes next, and how one covers the screen. The
 * wallpapers are pictures in a folder (the Breeze launcher puts Minecraft's
 * wallpapers in its home folder's {@code wallpapers}); the menu shows them in
 * name order, one per visit, starting after the one shown last.
 */
public final class WallpaperRotation {

    /** Larger files are skipped: a menu background needs no more. */
    public static final long MAX_BYTES = 12L * 1024 * 1024;

    private WallpaperRotation() {}

    /** The pictures in dir (JPEG or PNG), sorted by name; empty when there is no such folder. */
    public static List<Path> list(Path dir) {
        List<Path> out = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> isPicture(p.getFileName().toString()))
                    .filter(p -> {
                        try {
                            long size = Files.size(p);
                            return Files.isRegularFile(p) && size > 0 && size <= MAX_BYTES;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .sorted((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()))
                    .forEach(out::add);
        } catch (IOException ignored) {
        }
        return out;
    }

    public static boolean isPicture(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return !n.startsWith(".") && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png"));
    }

    /** The MIME type a data: URI needs for this file name. */
    public static String mime(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".png") ? "image/png" : "image/jpeg";
    }

    /**
     * The index of the wallpaper after {@code last} among {@code names} (wrapping
     * round), or 0 when last is unknown. -1 when there are none.
     */
    public static int next(List<String> names, String last) {
        if (names.isEmpty()) return -1;
        int i = last == null ? -1 : names.indexOf(last);
        return (i + 1) % names.size();
    }

    /**
     * The part of a texW by texH picture that covers a screen of the given
     * size without stretching: {u, v, width, height} in texture pixels, the
     * middle of the picture kept.
     */
    public static int[] cover(int texW, int texH, int screenW, int screenH) {
        if (texW <= 0 || texH <= 0 || screenW <= 0 || screenH <= 0) return new int[] {0, 0, Math.max(texW, 1), Math.max(texH, 1)};
        double screen = (double) screenW / screenH;
        double tex = (double) texW / texH;
        if (screen > tex) {
            int h = Math.max(1, (int) Math.round(texW / screen));
            return new int[] {0, (texH - h) / 2, texW, h};
        }
        int w = Math.max(1, (int) Math.round(texH * screen));
        return new int[] {(texW - w) / 2, 0, w, texH};
    }
}
