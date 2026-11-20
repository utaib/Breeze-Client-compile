package dev.breeze.cosmetics;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Writes a PNG without java.awt or Minecraft's image class (whose methods move
 * between versions): 8-bit RGBA, no filtering. Used by the self-test to put a
 * cape image in breeze_capes/ on every Minecraft version.
 */
public final class Png {

    private Png() {}

    /** A w by h image; argb(x, y) gives each pixel as 0xAARRGGBB. */
    public interface Pixels {
        int argb(int x, int y);
    }

    public static byte[] encode(int w, int h, Pixels pixels) {
        if (w <= 0 || h <= 0) throw new IllegalArgumentException("size " + w + "x" + h);
        byte[] raw = new byte[h * (1 + w * 4)];
        int i = 0;
        for (int y = 0; y < h; y++) {
            raw[i++] = 0; // filter: none
            for (int x = 0; x < w; x++) {
                int c = pixels.argb(x, y);
                raw[i++] = (byte) (c >>> 16);
                raw[i++] = (byte) (c >>> 8);
                raw[i++] = (byte) c;
                raw[i++] = (byte) (c >>> 24);
            }
        }
        Deflater d = new Deflater();
        d.setInput(raw);
        d.finish();
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!d.finished()) z.write(buf, 0, d.deflate(buf));
        d.end();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        byte[] ihdr = new byte[13];
        putInt(ihdr, 0, w);
        putInt(ihdr, 4, h);
        ihdr[8] = 8;  // bit depth
        ihdr[9] = 6;  // colour type: RGBA
        chunk(out, "IHDR", ihdr);
        chunk(out, "IDAT", z.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] len = new byte[4];
        putInt(len, 0, data.length);
        out.writeBytes(len);
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(t);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        byte[] c = new byte[4];
        putInt(c, 0, (int) crc.getValue());
        out.writeBytes(c);
    }

    private static void putInt(byte[] b, int at, int v) {
        b[at] = (byte) (v >>> 24);
        b[at + 1] = (byte) (v >>> 16);
        b[at + 2] = (byte) (v >>> 8);
        b[at + 3] = (byte) v;
    }
}
