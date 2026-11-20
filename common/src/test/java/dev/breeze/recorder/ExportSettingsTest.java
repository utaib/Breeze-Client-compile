package dev.breeze.recorder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExportSettingsTest {

    @Test
    void outputSizeKeepsShapeAndIsEven() {
        assertArrayEquals(new int[]{3840, 2160}, ExportSettings.outputSize(2160, 1920, 1080));
        assertArrayEquals(new int[]{1280, 720}, ExportSettings.outputSize(720, 854, 480));
        assertArrayEquals(new int[]{1920, 1080}, ExportSettings.outputSize(1080, 1280, 720));
        assertArrayEquals(new int[]{854, 480}, ExportSettings.outputSize(0, 855, 481));
        int[] odd = ExportSettings.outputSize(1440, 1001, 777);
        assertEquals(0, odd[0] % 2);
        assertEquals(1440, odd[1]);
    }

    @Test
    void savedAndReadBack(@TempDir Path dir) {
        ExportSettings s = ExportSettings.get(dir);
        assertEquals(1080, s.size);
        assertEquals(60, s.fps);
        s.nextSize(); // 1440
        s.nextSize(); // 2160
        s.nextFps();  // 120
        s.nextQuality(); // BEST
        s.toggleHardware();
        ExportSettings r = ExportSettings.read(dir.resolve("config/breeze_export.properties"));
        assertEquals(2160, r.size);
        assertEquals("4K", r.sizeLabel());
        assertEquals(120, r.fps);
        assertEquals(VideoEncoder.Quality.BEST, r.quality);
        assertFalse(r.hardware);
    }

    @Test
    void scaleOnlyWhenTheSizeDiffers() {
        VideoEncoder.Codec x264 = new VideoEncoder.Codec("libx264", "x264", false);
        List<String> up = VideoEncoder.command("ffmpeg", x264, VideoEncoder.Quality.HIGH, 1920, 1080, 60, Path.of("o.mp4"), false, 3840, 2160);
        assertTrue(String.join(" ", up).contains("scale=3840:2160:flags=lanczos"));
        List<String> same = VideoEncoder.command("ffmpeg", x264, VideoEncoder.Quality.HIGH, 1920, 1080, 60, Path.of("o.mp4"), false, 1920, 1080);
        assertFalse(String.join(" ", same).contains("scale="));
    }
}
