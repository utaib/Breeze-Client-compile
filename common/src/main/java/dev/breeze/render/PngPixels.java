package dev.breeze.render;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Counts the pixels of one colour in a PNG file: the self-test's way to see
 * whether something it drew reached the screen, from the screenshot Minecraft
 * saved (a counter of draw calls once said "drawn" for a label nobody could
 * see on 1.21 and later). Reads 8-bit RGB and RGBA files without interlacing,
 * which is what Minecraft writes; anything else is refused. Pure Java, no AWT,
 * so it runs inside any game.
 */
public final class PngPixels {

    private PngPixels() {}

    /** The largest picture read: 64 million pixels (an 8K screen is 33 million). */
    static final long MAX_PIXELS = 64L * 1024 * 1024;

    /**
     * How many pixels are within {@code tolerance} of {@code rgb} (0xRRGGBB) on
     * every channel. Throws IllegalArgumentException for a file it cannot read.
     */
    public static int count(byte[] png, int rgb, int tolerance) {
        int wantR = (rgb >> 16) & 0xFF, wantG = (rgb >> 8) & 0xFF, wantB = rgb & 0xFF;
        if (png == null || png.length < 8 || (png[0] & 0xFF) != 0x89 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G') {
            throw new IllegalArgumentException("not a PNG file");
        }
        int pos = 8, width = 0, height = 0, channels = 0;
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        boolean header = false;
        while (pos + 8 <= png.length) {
            int len = readInt(png, pos);
            String type = new String(png, pos + 4, 4, StandardCharsets.US_ASCII);
            int data = pos + 8;
            if (len < 0 || data + len + 4 > png.length) throw new IllegalArgumentException("PNG chunk runs past the end");
            if (type.equals("IHDR")) {
                width = readInt(png, data);
                height = readInt(png, data + 4);
                int depth = png[data + 8] & 0xFF, colour = png[data + 9] & 0xFF, interlace = png[data + 12] & 0xFF;
                if (depth != 8 || interlace != 0 || (colour != 2 && colour != 6)) {
                    throw new IllegalArgumentException("only 8-bit RGB or RGBA without interlacing is read");
                }
                if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) {
                    throw new IllegalArgumentException("picture size out of range");
                }
                channels = colour == 6 ? 4 : 3;
                header = true;
            } else if (type.equals("IDAT")) {
                idat.write(png, data, len);
            } else if (type.equals("IEND")) {
                break;
            }
            pos = data + len + 4; // the CRC is not checked: the file is our own screenshot
        }
        if (!header) throw new IllegalArgumentException("PNG without a header");
        int stride = width * channels;
        byte[] raw = inflate(idat.toByteArray(), (long) (stride + 1) * height);
        byte[] prev = new byte[stride];
        byte[] row = new byte[stride];
        int found = 0;
        for (int y = 0; y < height; y++) {
            int base = y * (stride + 1);
            int filter = raw[base] & 0xFF;
            for (int i = 0; i < stride; i++) {
                int x = raw[base + 1 + i] & 0xFF;
                int a = i >= channels ? row[i - channels] & 0xFF : 0;
                int b = prev[i] & 0xFF;
                int c = i >= channels ? prev[i - channels] & 0xFF : 0;
                int v;
                switch (filter) {
                    case 0: v = x; break;
                    case 1: v = x + a; break;
                    case 2: v = x + b; break;
                    case 3: v = x + ((a + b) >> 1); break;
                    case 4: v = x + paeth(a, b, c); break;
                    default: throw new IllegalArgumentException("unknown PNG row filter " + filter);
                }
                row[i] = (byte) v;
            }
            for (int i = 0; i + 2 < stride; i += channels) {
                if (Math.abs((row[i] & 0xFF) - wantR) <= tolerance
                        && Math.abs((row[i + 1] & 0xFF) - wantG) <= tolerance
                        && Math.abs((row[i + 2] & 0xFF) - wantB) <= tolerance) found++;
            }
            byte[] t = prev; prev = row; row = t;
        }
        return found;
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        if (pa <= pb && pa <= pc) return a;
        return pb <= pc ? b : c;
    }

    private static byte[] inflate(byte[] data, long expected) {
        if (expected > Integer.MAX_VALUE - 8) throw new IllegalArgumentException("picture too large");
        byte[] out = new byte[(int) expected];
        Inflater inf = new Inflater();
        try {
            inf.setInput(data);
            int n = 0;
            while (n < out.length && !inf.finished()) {
                int got = inf.inflate(out, n, out.length - n);
                if (got == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                n += got;
            }
            if (n != out.length) throw new IllegalArgumentException("PNG data is shorter than the picture");
            return out;
        } catch (DataFormatException e) {
            throw new IllegalArgumentException("PNG data is damaged");
        } finally {
            inf.end();
        }
    }

    private static int readInt(byte[] b, int at) {
        return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
    }
}
