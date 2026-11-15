package dev.breeze.render;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.*;

class PngPixelsTest {

    private static final int MAGENTA = 0xFF00FF;

    /** A picture with {@code marks} magenta pixels on grass, as ImageIO writes it. */
    private static byte[] imageIoPng(int type, int marks) throws Exception {
        BufferedImage img = new BufferedImage(64, 40, type);
        for (int y = 0; y < 40; y++)
            for (int x = 0; x < 64; x++) img.setRGB(x, y, 0xFF5D8A3A + ((x * 3 + y) & 0x0F));
        for (int i = 0; i < marks; i++) img.setRGB(5 + i % 50, 10 + i / 50, 0xFFFF00FF);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(img, "png", out));
        return out.toByteArray();
    }

    @Test
    void countsOneColourInRgbAndRgbaFiles() throws Exception {
        assertEquals(37, PngPixels.count(imageIoPng(BufferedImage.TYPE_INT_ARGB, 37), MAGENTA, 8));
        assertEquals(120, PngPixels.count(imageIoPng(BufferedImage.TYPE_INT_RGB, 120), MAGENTA, 8));
        assertEquals(0, PngPixels.count(imageIoPng(BufferedImage.TYPE_INT_RGB, 0), MAGENTA, 8));
    }

    @Test
    void everyRowFilterIsUndone() {
        // Rows filtered with each of PNG's five filters in turn, so each one is
        // read back exactly (ImageIO picks its own and may never use some).
        int w = 7, h = 10, ch = 4;
        byte[][] pixels = new byte[h][w * ch];
        int marks = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                boolean mark = (x + y) % 3 == 0;
                if (mark) marks++;
                int rgb = mark ? MAGENTA : 0x203040 + x * 9 + y * 17;
                pixels[y][x * ch] = (byte) (rgb >> 16);
                pixels[y][x * ch + 1] = (byte) (rgb >> 8);
                pixels[y][x * ch + 2] = (byte) rgb;
                pixels[y][x * ch + 3] = (byte) 0xFF;
            }
        assertEquals(marks, PngPixels.count(filteredPng(pixels, w, h, ch), MAGENTA, 0));
    }

    @Test
    void refusesWhatItCannotRead() {
        assertThrows(IllegalArgumentException.class, () -> PngPixels.count(new byte[]{1, 2, 3}, MAGENTA, 8));
        assertThrows(IllegalArgumentException.class, () -> PngPixels.count("not a png at all".getBytes(StandardCharsets.US_ASCII), MAGENTA, 8));
        byte[] png = filteredPng(new byte[][]{{1, 2, 3, 4}}, 1, 1, 4);
        png[8 + 8 + 8] = 16; // IHDR bit depth 16
        assertThrows(IllegalArgumentException.class, () -> PngPixels.count(png, MAGENTA, 8));
    }

    private static byte[] filteredPng(byte[][] rows, int w, int h, int ch) {
        int stride = w * ch;
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        byte[] prev = new byte[stride];
        for (int y = 0; y < h; y++) {
            int f = y % 5;
            raw.write(f);
            byte[] row = rows[y];
            for (int i = 0; i < stride; i++) {
                int x = row[i] & 0xFF, a = i >= ch ? row[i - ch] & 0xFF : 0, b = prev[i] & 0xFF, c = i >= ch ? prev[i - ch] & 0xFF : 0;
                int pred = f == 0 ? 0 : f == 1 ? a : f == 2 ? b : f == 3 ? (a + b) >> 1 : paeth(a, b, c);
                raw.write((x - pred) & 0xFF);
            }
            prev = row;
        }
        Deflater d = new Deflater();
        d.setInput(raw.toByteArray());
        d.finish();
        byte[] buf = new byte[4096];
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        while (!d.finished()) z.write(buf, 0, d.deflate(buf));
        d.end();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
        byte[] ihdr = {0, 0, 0, (byte) w, 0, 0, 0, (byte) h, 8, (byte) (ch == 4 ? 6 : 2), 0, 0, 0};
        chunk(png, "IHDR", ihdr);
        chunk(png, "IDAT", z.toByteArray());
        chunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        if (pa <= pb && pa <= pc) return a;
        return pb <= pc ? b : c;
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        out.write(data.length >>> 24); out.write(data.length >>> 16); out.write(data.length >>> 8); out.write(data.length);
        out.writeBytes(t);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        long v = crc.getValue();
        out.write((int) (v >>> 24)); out.write((int) (v >>> 16)); out.write((int) (v >>> 8)); out.write((int) v);
    }
}
