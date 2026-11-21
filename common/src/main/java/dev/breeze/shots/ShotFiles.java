package dev.breeze.shots;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The screenshots folder as Breeze's screenshot actions see it: the pictures
 * in it, newest first, and a name from chat turned back into a file only when
 * it names a picture directly inside that folder (a chat line is text anyone
 * can write, so ".." or a path never leaves the folder).
 */
public final class ShotFiles {

    private ShotFiles() {}

    /** One picture: its file name, its size in bytes and when it was last written (ms since 1970). */
    public record Shot(String name, long size, long modified) {}

    /** Whether a file name is one of the pictures Breeze lists (Minecraft writes PNG; JPEG from other tools). */
    public static boolean isPicture(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg");
    }

    /** The pictures directly in dir, newest first (then by name); empty when the folder is missing. */
    public static List<Shot> list(Path dir) {
        List<Shot> out = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                if (!isPicture(name) || !Files.isRegularFile(p)) continue;
                try {
                    out.add(new Shot(name, Files.size(p), Files.getLastModifiedTime(p).toMillis()));
                } catch (IOException ignored) {
                    // Gone between listing and reading: not listed.
                }
            }
        } catch (IOException ignored) {
            return out;
        }
        out.sort(Comparator.comparingLong(Shot::modified).reversed().thenComparing(Shot::name, Comparator.reverseOrder()));
        return out;
    }

    /**
     * The picture in dir with exactly this file name, or null when the name is
     * not a plain picture file name (separators, "..", control characters) or
     * no such file is there.
     */
    public static Path resolve(Path dir, String name) {
        if (dir == null || !safeName(name)) return null;
        Path p = dir.resolve(name).normalize();
        if (!dir.normalize().equals(p.getParent())) return null;
        return Files.isRegularFile(p) ? p : null;
    }

    /** A bare picture file name: no folder parts, nothing hidden, nothing a terminal or shell could read as more. */
    public static boolean safeName(String name) {
        if (name == null || name.isEmpty() || name.length() > 200 || !isPicture(name)) return false;
        if (name.startsWith(".") || name.contains("..")) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '/' || c == '\\' || c == ':' || c == '"' || c == '*' || c == '?'
                    || c == '<' || c == '>' || c == '|') return false;
        }
        return true;
    }

    /**
     * The picture a screenshot message names: the newest picture in dir
     * written at or after sinceMs whose name appears in the message text.
     * Minecraft's message is translated, but always carries the file name, so
     * this works in every language. Null when none matches (a failed
     * screenshot, or one saved elsewhere).
     */
    public static Shot named(Path dir, String messageText, long sinceMs) {
        if (messageText == null) return null;
        // The file the message names, looked at directly: listing the whole
        // folder read the size and time of every picture in it after each
        // screenshot (hundreds for a player who keeps them).
        java.util.regex.Matcher m = NAME_IN_TEXT.matcher(messageText);
        while (m.find()) {
            Path p = resolve(dir, m.group());
            if (p == null) continue;
            try {
                long modified = Files.getLastModifiedTime(p).toMillis();
                if (modified >= sinceMs) return new Shot(m.group(), Files.size(p), modified);
            } catch (IOException ignored) {
                // Gone meanwhile: the listing below decides.
            }
        }
        for (Shot s : list(dir)) {
            if (s.modified() < sinceMs) break;
            if (messageText.contains(s.name())) return s;
        }
        return null;
    }

    private static final java.util.regex.Pattern NAME_IN_TEXT =
            java.util.regex.Pattern.compile("[^\\s\"'/\\\\]+\\.(?i:png|jpe?g)");

    /** A size for people: 512 B, 3.4 KB, 2.1 MB. */
    public static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
