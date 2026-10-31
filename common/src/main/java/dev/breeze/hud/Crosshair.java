package dev.breeze.hud;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;

/**
 * The Custom Crosshair: its settings, its shape and its share code, apart
 * from Minecraft so the shape the game draws and the code players trade are
 * tested here. The menu's preview draws the same shape from the same rules
 * (frontend/src/app/crosshair.ts); contract/crosshair.json holds examples
 * both sides are tested against.
 *
 * The shape is a list of filled rectangles around the centre pixel, in GUI
 * pixels (the units Minecraft's own crosshair is drawn in, so it scales with
 * the GUI scale as the vanilla one does). Outline rectangles come first, so
 * the crosshair is drawn over its outline.
 *
 * A share code is "BZC-" and the settings as bytes in base64url, with a
 * version byte first and a checksum last. Only these numbers are read from
 * it, each checked against its range: a code cannot carry anything else,
 * and a damaged or foreign one is refused with a reason. A later format
 * gets a new version number and this keeps reading version 1.
 */
public final class Crosshair {

    public static final String[] STYLES = {"Cross", "T", "Dot"};
    public static final int LENGTH_MIN = 1, LENGTH_MAX = 20;
    public static final int THICKNESS_MIN = 1, THICKNESS_MAX = 8;
    public static final int GAP_MIN = 0, GAP_MAX = 12;
    public static final int OUTLINE_MIN = 1, OUTLINE_MAX = 3;

    public static final String PREFIX = "BZC-";
    static final int VERSION = 1;
    private static final int V1_BYTES = 17;

    /** The settings a crosshair is made of. Colours are ARGB. */
    public record Config(String style, int length, int thickness, int gap, int color,
                         boolean dot, boolean outline, int outlineThickness, int outlineColor) {

        public static Config defaults() {
            return new Config("Cross", 4, 1, 2, 0xFFFFFFFF, false, true, 1, 0xFF000000);
        }

        /** The same settings with every number brought into its range and an unknown style made Cross. */
        public Config clamped() {
            return new Config(styleIndex(style) < 0 ? "Cross" : STYLES[styleIndex(style)],
                    clamp(length, LENGTH_MIN, LENGTH_MAX), clamp(thickness, THICKNESS_MIN, THICKNESS_MAX),
                    clamp(gap, GAP_MIN, GAP_MAX), color, dot, outline,
                    clamp(outlineThickness, OUTLINE_MIN, OUTLINE_MAX), outlineColor);
        }
    }

    /** A filled rectangle, x0 and y0 included, x1 and y1 not, relative to the centre pixel. */
    public record Rect(int x0, int y0, int x1, int y1, int color) {}

    /** What reading a code gave: the settings, or why it was refused. */
    public record Decoded(Config config, String error) {
        public boolean ok() { return config != null; }
    }

    private Crosshair() {}

    // ── Shape ───────────────────────────────────────────────────────────

    public static List<Rect> rects(Config raw) {
        Config c = raw.clamped();
        int t = c.thickness();
        int s = -(t / 2);
        int e = s + t;
        int g = c.gap();
        int l = c.length();
        List<Rect> shape = new ArrayList<>();
        switch (c.style()) {
            case "Dot" -> shape.add(new Rect(s, s, e, e, c.color()));
            default -> {
                shape.add(new Rect(s - g - l, s, s - g, e, c.color()));   // left
                shape.add(new Rect(e + g, s, e + g + l, e, c.color()));   // right
                if (!c.style().equals("T")) shape.add(new Rect(s, s - g - l, e, s - g, c.color()));   // top
                shape.add(new Rect(s, e + g, e, e + g + l, c.color()));   // bottom
                if (c.dot()) shape.add(new Rect(s, s, e, e, c.color()));
            }
        }
        if (!c.outline()) return shape;
        int o = c.outlineThickness();
        List<Rect> out = new ArrayList<>(shape.size() * 2);
        for (Rect r : shape) out.add(new Rect(r.x0() - o, r.y0() - o, r.x1() + o, r.y1() + o, c.outlineColor()));
        out.addAll(shape);
        return out;
    }

    // ── Share codes ─────────────────────────────────────────────────────

    public static String encode(Config raw) {
        Config c = raw.clamped();
        byte[] b = new byte[V1_BYTES];
        b[0] = VERSION;
        b[1] = (byte) styleIndex(c.style());
        b[2] = (byte) c.length();
        b[3] = (byte) c.thickness();
        b[4] = (byte) c.gap();
        b[5] = (byte) ((c.dot() ? 1 : 0) | (c.outline() ? 2 : 0));
        b[6] = (byte) c.outlineThickness();
        putInt(b, 7, c.color());
        putInt(b, 11, c.outlineColor());
        int crc = crc16(b, 15);
        b[15] = (byte) (crc >>> 8);
        b[16] = (byte) crc;
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static Decoded decode(String code) {
        String text = code == null ? "" : code.strip();
        if (text.isEmpty()) return refuse("Paste a crosshair code.");
        if (!text.toUpperCase(Locale.ROOT).startsWith(PREFIX)) {
            return refuse("That is not a Breeze crosshair code. They start with " + PREFIX);
        }
        String body = text.substring(PREFIX.length());
        if (body.length() > 64 || !body.matches("[A-Za-z0-9_-]+")) return damaged();
        byte[] b;
        try {
            b = Base64.getUrlDecoder().decode(body.getBytes(StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException ex) {
            return damaged();
        }
        if (b.length == 0) return damaged();
        int version = b[0] & 0xFF;
        if (version > VERSION) return refuse("That code is from a newer Breeze. Update Breeze to use it.");
        if (version == 1) return decodeV1(b);
        return damaged();
    }

    private static Decoded decodeV1(byte[] b) {
        if (b.length != V1_BYTES) return damaged();
        int crc = ((b[15] & 0xFF) << 8) | (b[16] & 0xFF);
        if (crc != crc16(b, 15)) return damaged();
        int style = b[1] & 0xFF, length = b[2] & 0xFF, thickness = b[3] & 0xFF, gap = b[4] & 0xFF;
        int flags = b[5] & 0xFF, outline = b[6] & 0xFF;
        if (style >= STYLES.length || (flags & ~3) != 0
                || !in(length, LENGTH_MIN, LENGTH_MAX) || !in(thickness, THICKNESS_MIN, THICKNESS_MAX)
                || !in(gap, GAP_MIN, GAP_MAX) || !in(outline, OUTLINE_MIN, OUTLINE_MAX)) {
            return refuse("That code has a setting this Breeze cannot show. Ask for a new code.");
        }
        return new Decoded(new Config(STYLES[style], length, thickness, gap, getInt(b, 7),
                (flags & 1) != 0, (flags & 2) != 0, outline, getInt(b, 11)), null);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    public static int styleIndex(String style) {
        for (int i = 0; i < STYLES.length; i++) if (STYLES[i].equalsIgnoreCase(style)) return i;
        return -1;
    }

    private static Decoded refuse(String why) {
        return new Decoded(null, why);
    }

    private static Decoded damaged() {
        return refuse("That code is damaged or incomplete. Copy it again.");
    }

    private static boolean in(int v, int min, int max) {
        return v >= min && v <= max;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static void putInt(byte[] b, int at, int v) {
        b[at] = (byte) (v >>> 24);
        b[at + 1] = (byte) (v >>> 16);
        b[at + 2] = (byte) (v >>> 8);
        b[at + 3] = (byte) v;
    }

    private static int getInt(byte[] b, int at) {
        return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
    }

    private static int crc16(byte[] b, int len) {
        CRC32 crc = new CRC32();
        crc.update(b, 0, len);
        return (int) (crc.getValue() & 0xFFFF);
    }
}
