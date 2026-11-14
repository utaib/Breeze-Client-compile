package dev.breeze.cosmetics;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;

class PngTest {

    @Test
    void anotherDecoderReadsBackEveryPixel() throws Exception {
        byte[] png = Png.encode(64, 32, (x, y) -> x < 32 ? 0xFFE0302C : (y < 16 ? 0x8040A0FF : 0x00000000));
        assertEquals("image/png", ImageData.mimeOf(png));
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(img);
        assertEquals(64, img.getWidth());
        assertEquals(32, img.getHeight());
        assertEquals(0xFFE0302C, img.getRGB(5, 20));
        assertEquals(0x8040A0FF, img.getRGB(40, 3));
        assertEquals(0, img.getRGB(40, 20) >>> 24, "transparent stays transparent");
    }

    @Test
    void refusesAnEmptyImage() {
        assertThrows(IllegalArgumentException.class, () -> Png.encode(0, 32, (x, y) -> 0));
    }
}
