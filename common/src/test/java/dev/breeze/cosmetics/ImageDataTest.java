package dev.breeze.cosmetics;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class ImageDataTest {

    private static byte[] encode(String format) throws Exception {
        BufferedImage img = new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB);
        img.setRGB(1, 1, 0x3366FF);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(img, format, out), format);
        return out.toByteArray();
    }

    @Test
    void realImagesAreRecognisedByTheirSignature() throws Exception {
        assertEquals("image/png", ImageData.mimeOf(encode("png")));
        assertEquals("image/gif", ImageData.mimeOf(encode("gif")));
        assertEquals("image/jpeg", ImageData.mimeOf(encode("jpg")));
    }

    @Test
    void anythingElseIsRefused() {
        assertNull(ImageData.mimeOf(null));
        assertNull(ImageData.mimeOf(new byte[0]));
        assertNull(ImageData.mimeOf("<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes(StandardCharsets.UTF_8)));
        assertNull(ImageData.mimeOf("<!doctype html><script>".getBytes(StandardCharsets.UTF_8)));
        assertNull(ImageData.dataUri("not an image".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void theUriCarriesTheExactBytes() throws Exception {
        byte[] png = encode("png");
        String uri = ImageData.dataUri(png);
        assertNotNull(uri);
        assertTrue(uri.startsWith("data:image/png;base64,"));
        assertArrayEquals(png, Base64.getDecoder().decode(uri.substring(uri.indexOf(',') + 1)));
    }

    @Test
    void oversizedImagesAreLeftOut() throws Exception {
        byte[] png = encode("png");
        byte[] big = Arrays.copyOf(png, ImageData.MAX_BYTES + 1);
        assertEquals("image/png", ImageData.mimeOf(big));
        assertNull(ImageData.dataUri(big));
    }
}
