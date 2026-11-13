package dev.breeze.music;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Timed lyrics in the LRC format: "[01:23.45] a line of the song".
 *
 * A line may carry several times (a repeated chorus), "[offset:+250]" shifts
 * every time, tags such as "[ar: artist]" are not lyrics, and "enhanced"
 * files add a time before each word ("<01:23.90>word"), which is dropped.
 *
 * The text comes from the internet and ends up drawn in the game, so every
 * line is cleaned: no control or formatting characters, one line, a limited
 * length; the number of lines is limited too.
 */
public final class Lrc {

    private Lrc() {}

    /** One line and when it starts, in milliseconds from the start of the song. */
    public static final class Line {
        public final long atMs;
        public final String text;

        public Line(long atMs, String text) {
            this.atMs = atMs;
            this.text = text;
        }
    }

    static final int MAX_LINES = 2_000;
    static final int MAX_CHARS = 160;
    static final int MAX_INPUT = 300_000;

    private static final Pattern TIME = Pattern.compile("^\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern WORD_TIME = Pattern.compile("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>");
    private static final Pattern OFFSET = Pattern.compile("^\\[offset:\\s*([+-]?\\d{1,7})\\s*]$", Pattern.CASE_INSENSITIVE);

    /** The timed lines, earliest first. Empty when the text has no times. */
    public static List<Line> parse(String lrc) {
        List<Line> out = new ArrayList<>();
        if (lrc == null) return out;
        if (lrc.length() > MAX_INPUT) lrc = lrc.substring(0, MAX_INPUT);
        long offset = 0;
        for (String raw : lrc.split("\\r?\\n|\\r")) {
            String line = raw.trim();
            if (line.isEmpty() || line.charAt(0) != '[') continue;
            Matcher o = OFFSET.matcher(line);
            if (o.matches()) {
                // A positive offset makes the lyrics come sooner.
                offset = Long.parseLong(o.group(1).replace("+", ""));
                continue;
            }
            List<Long> times = new ArrayList<>();
            Matcher t = TIME.matcher(line);
            while (t.find()) {
                times.add(millis(t.group(1), t.group(2), t.group(3)));
                line = line.substring(t.end()).trim();
                t = TIME.matcher(line);
            }
            if (times.isEmpty()) continue;
            String text = clean(WORD_TIME.matcher(line).replaceAll(""));
            for (long at : times) {
                if (out.size() >= MAX_LINES) break;
                out.add(new Line(at, text));
            }
        }
        if (offset != 0) {
            List<Line> shifted = new ArrayList<>(out.size());
            for (Line l : out) shifted.add(new Line(Math.max(0, l.atMs - offset), l.text));
            out = shifted;
        }
        // Stable, so lines sharing a time keep the order they were written in.
        out.sort(Comparator.comparingLong(l -> l.atMs));
        return Collections.unmodifiableList(out);
    }

    private static long millis(String min, String sec, String fraction) {
        long ms = Long.parseLong(min) * 60_000 + Long.parseLong(sec) * 1_000;
        if (fraction != null) {
            // ".5" is half a second, ".45" 450 ms, ".450" 450 ms.
            int f = Integer.parseInt(fraction);
            ms += fraction.length() == 1 ? f * 100L : fraction.length() == 2 ? f * 10L : f;
        }
        return ms;
    }

    /** The line being sung at a position: the last one that has started, or -1 before the first. */
    public static int indexAt(List<Line> lines, long positionMs) {
        int lo = 0, hi = lines.size() - 1, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).atMs <= positionMs) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }

    /** Lyrics without times, one entry per line, cleaned the same way. */
    public static List<String> plain(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        if (text.length() > MAX_INPUT) text = text.substring(0, MAX_INPUT);
        for (String raw : text.split("\\r?\\n|\\r")) {
            if (out.size() >= MAX_LINES) break;
            out.add(clean(raw));
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
        return Collections.unmodifiableList(out);
    }

    /** One safe line: no control characters, no Minecraft formatting codes, spaces collapsed, at most MAX_CHARS. */
    static String clean(String s) {
        StringBuilder b = new StringBuilder(Math.min(s.length(), MAX_CHARS));
        boolean space = false;
        for (int i = 0; i < s.length() && b.length() < MAX_CHARS; i++) {
            char c = s.charAt(i);
            if (c == '\u00a7') {
                i++;
                continue;
            }
            if (Character.isISOControl(c) || Character.isWhitespace(c) || c == '\uFEFF') {
                space = b.length() > 0;
                continue;
            }
            if (space) b.append(' ');
            space = false;
            b.append(c);
        }
        return b.toString();
    }

    /**
     * A line split to fit a width, at spaces where it can: lyrics drawn in
     * the world or on the HUD wrap instead of running off. {@code measure}
     * gives a text's width; at most maxLines come back, the last one cut.
     */
    public static List<String> wrap(String text, int maxWidth, int maxLines, java.util.function.ToIntFunction<String> measure) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty() || maxLines <= 0) return out;
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = line.length() == 0 ? word : line + " " + word;
            if (measure.applyAsInt(next) <= maxWidth) {
                line.setLength(0);
                line.append(next);
                continue;
            }
            if (line.length() > 0) {
                out.add(line.toString());
                if (out.size() == maxLines) return out;
            }
            // A single word wider than the line is cut to fit.
            String w = word;
            while (w.length() > 1 && measure.applyAsInt(w) > maxWidth) w = w.substring(0, w.length() - 1);
            line.setLength(0);
            line.append(w);
        }
        if (line.length() > 0 && out.size() < maxLines) out.add(line.toString());
        return out;
    }
}
