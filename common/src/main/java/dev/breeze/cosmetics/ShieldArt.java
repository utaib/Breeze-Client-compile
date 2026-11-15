package dev.breeze.cosmetics;

/**
 * A custom shield's texture: the player's picture on the front of
 * Minecraft's own shield, the rest (rim, back, handle) as the game draws it.
 *
 * Minecraft's shield texture is 64x64; the shield model's plate is a box
 * 12 wide, 22 high and 1 deep at texture offset (0, 0), so its front face
 * (where banner patterns go) is the 12x22 area at (1, 1). The model's
 * coordinates are fractions of the texture, so the same layout drawn larger
 * (64 times a scale) gives the picture more detail on the same shield.
 */
public final class ShieldArt {

    /** The game's shield texture is this many pixels a side. */
    public static final int BASE = 64;
    public static final int FACE_X = 1;
    public static final int FACE_Y = 1;
    public static final int FACE_W = 12;
    public static final int FACE_H = 22;
    /** Up to 8 times: a 512x512 texture with a 96x176 face. */
    public static final int MAX_SCALE = 8;
    /** Pictures larger than this a side are refused before decoding them all. */
    public static final int MAX_SIDE = 2048;

    public enum Fit { COVER, CONTAIN }

    /** Longest picture file name kept. */
    public static final int MAX_NAME = 120;

    private ShieldArt() {}

    /**
     * A picture's file name in the breeze_shields folder, as the Custom
     * Shield setting keeps it: a name only (no folder, no way out of the
     * folder), ending .png, .jpg or .jpeg; empty for none. Throws with the
     * reason the player reads.
     */
    public static String cleanPictureName(String raw) {
        String s = raw == null ? "" : raw.strip();
        if (s.isEmpty()) return "";
        if (s.length() > MAX_NAME) throw new IllegalArgumentException("Use a file name of at most " + MAX_NAME + " characters.");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) throw new IllegalArgumentException("A file name cannot hold control characters.");
            if (c == '/' || c == '\\' || c == ':') throw new IllegalArgumentException("Give the file's name only, from the breeze_shields folder.");
        }
        if (s.equals(".") || s.equals("..") || s.startsWith(".")) throw new IllegalArgumentException("Give the file's name only, from the breeze_shields folder.");
        String lower = s.toLowerCase(java.util.Locale.ROOT);
        if (!lower.endsWith(".png") && !lower.endsWith(".jpg") && !lower.endsWith(".jpeg")) {
            throw new IllegalArgumentException("Use a PNG or JPEG picture.");
        }
        return s;
    }

    /** The smallest scale that shows the picture at its own detail, at most MAX_SCALE. */
    public static int scaleFor(int imageW, int imageH) {
        int s = 1;
        while (s < MAX_SCALE && (FACE_W * s < imageW || FACE_H * s < imageH)) s *= 2;
        return s;
    }

    /**
     * The shield texture, ARGB, (64 * scale) pixels a side: base (the game's
     * 64x64 shield, ARGB) enlarged, with the picture drawn over the front
     * face. COVER fills the face and crops the picture's longer side; CONTAIN
     * shows all of it and leaves the shield's own face around it. A
     * see-through picture lets the shield show through.
     */
    public static int[] compose(int[] base, int[] image, int imageW, int imageH, int scale, Fit fit) {
        if (base.length != BASE * BASE) throw new IllegalArgumentException("base must be 64x64");
        if (imageW <= 0 || imageH <= 0 || image.length != imageW * imageH) throw new IllegalArgumentException("bad picture size");
        if (scale < 1 || scale > MAX_SCALE) throw new IllegalArgumentException("bad scale");
        int size = BASE * scale;
        int[] out = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) out[y * size + x] = base[(y / scale) * BASE + x / scale];
        }
        int fx = FACE_X * scale, fy = FACE_Y * scale, fw = FACE_W * scale, fh = FACE_H * scale;
        // The picture's rectangle within the face, in face pixels.
        double imageAspect = imageW / (double) imageH, faceAspect = fw / (double) fh;
        double dw = fw, dh = fh;
        boolean wider = imageAspect > faceAspect;
        if (fit == Fit.CONTAIN) {
            if (wider) dh = fw / imageAspect;
            else dw = fh * imageAspect;
        } else {
            if (wider) dw = fh * imageAspect;
            else dh = fw / imageAspect;
        }
        double ox = (fw - dw) / 2, oy = (fh - dh) / 2;
        for (int y = 0; y < fh; y++) {
            for (int x = 0; x < fw; x++) {
                // This face pixel's footprint in the picture.
                double u0 = (x - ox) / dw * imageW, u1 = (x + 1 - ox) / dw * imageW;
                double v0 = (y - oy) / dh * imageH, v1 = (y + 1 - oy) / dh * imageH;
                if (u1 <= 0 || v1 <= 0 || u0 >= imageW || v0 >= imageH) continue;
                int src = average(image, imageW, imageH, u0, v0, u1, v1);
                int at = (fy + y) * size + fx + x;
                out[at] = over(src, out[at]);
            }
        }
        return out;
    }

    /** The picture's average colour over [u0, u1) x [v0, v1), or the pixel there when smaller than one. */
    private static int average(int[] img, int w, int h, double u0, double v0, double u1, double v1) {
        int x0 = clamp((int) Math.floor(u0), w), x1 = clamp((int) Math.ceil(u1) - 1, w);
        int y0 = clamp((int) Math.floor(v0), h), y1 = clamp((int) Math.ceil(v1) - 1, h);
        if (x1 <= x0 && y1 <= y0) return img[y0 * w + x0];
        long a = 0, r = 0, g = 0, b = 0;
        int n = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                int c = img[y * w + x];
                int ca = c >>> 24;
                a += ca;
                // Colours weighted by alpha, so see-through pixels do not darken edges.
                r += (long) ((c >> 16) & 0xFF) * ca;
                g += (long) ((c >> 8) & 0xFF) * ca;
                b += (long) (c & 0xFF) * ca;
                n++;
            }
        }
        if (a == 0) return 0;
        return (int) (a / n) << 24 | (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a);
    }

    private static int clamp(int v, int size) {
        return Math.max(0, Math.min(size - 1, v));
    }

    /** src drawn over dst (both ARGB, not premultiplied). */
    static int over(int src, int dst) {
        int sa = src >>> 24;
        if (sa == 255) return src;
        if (sa == 0) return dst;
        int da = dst >>> 24;
        int oa = sa + da * (255 - sa) / 255;
        if (oa == 0) return 0;
        int r = (((src >> 16) & 0xFF) * sa + ((dst >> 16) & 0xFF) * da * (255 - sa) / 255) / oa;
        int g = (((src >> 8) & 0xFF) * sa + ((dst >> 8) & 0xFF) * da * (255 - sa) / 255) / oa;
        int b = ((src & 0xFF) * sa + (dst & 0xFF) * da * (255 - sa) / 255) / oa;
        return oa << 24 | r << 16 | g << 8 | b;
    }
}
