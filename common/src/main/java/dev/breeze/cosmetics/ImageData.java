package dev.breeze.cosmetics;

import java.util.Base64;

/**
 * Turns a downloaded image into a data: URI the menu can show.
 *
 * The page is not allowed to load anything from the network (its content
 * policy has connect-src 'none' and img-src 'self' data:), so a cape preview
 * reaches it as data inside the bridge answer. Only real PNG, GIF and JPEG
 * files are passed on, recognised by their first bytes rather than trusted
 * from a header, and only up to a size that keeps the answer small.
 */
public final class ImageData {

    /** Larger than any cape texture Breeze serves (a 512x256 PNG is well under this). */
    public static final int MAX_BYTES = 256 * 1024;

    private ImageData() {}

    /** The image type from the file's own signature, or null for anything else. */
    public static String mimeOf(byte[] b) {
        if (b == null) return null;
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) return "image/png";
        if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8'
                && (b[4] == '7' || b[4] == '9') && b[5] == 'a') return "image/gif";
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        return null;
    }

    /**
     * A PNG's width and height from its header (the IHDR chunk, which the
     * format puts first), or null when the bytes are not a PNG.
     */
    public static int[] pngSize(byte[] b) {
        if (!"image/png".equals(mimeOf(b)) || b.length < 24) return null;
        if (b[12] != 'I' || b[13] != 'H' || b[14] != 'D' || b[15] != 'R') return null;
        int w = int32(b, 16), h = int32(b, 20);
        return w > 0 && h > 0 ? new int[]{w, h} : null;
    }

    private static int int32(byte[] b, int at) {
        return (b[at] & 0xFF) << 24 | (b[at + 1] & 0xFF) << 16 | (b[at + 2] & 0xFF) << 8 | (b[at + 3] & 0xFF);
    }

    /** A data: URI for the image, or null when it is not a PNG, GIF or JPEG, or is too large. */
    public static String dataUri(byte[] b) {
        String mime = mimeOf(b);
        if (mime == null || b.length > MAX_BYTES) return null;
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(b);
    }
}
