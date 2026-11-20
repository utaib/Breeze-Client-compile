package dev.breeze.recorder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 1.2.3's second part: the camera of every frame, any frame rate, H.264 levels. */
class CameraAndRatesTest {

    @TempDir
    Path dir;

    private static RecMeta meta() {
        RecMeta m = new RecMeta();
        m.minecraft = "1.21.11";
        m.loader = "fabric";
        return m;
    }

    /** A SELF record as RecSelf writes it: format, x y z, yaw, pitch, head, body, flags, slot, screen. */
    private static byte[] self(float yaw, float pitch) {
        ByteBuffer b = ByteBuffer.allocate(1 + 24 + 16 + 3);
        b.put((byte) 1).putDouble(1).putDouble(2).putDouble(3).putFloat(yaw).putFloat(pitch).putFloat(yaw).putFloat(yaw);
        b.put((byte) 0).put((byte) 0).put((byte) 0);
        return b.array();
    }

    @Test
    void frameSamplesRoundTrip() {
        byte[] b = new byte[CameraFrames.SIZE];
        CameraFrames.encode(b, 437, -1234.5f, 33.25f);
        assertEquals(437, CameraFrames.micros(b));
        assertEquals(-1234.5f, CameraFrames.yaw(b));
        assertEquals(33.25f, CameraFrames.pitch(b));
        CameraFrames.encode(b, 5000, 0, 0);
        assertEquals(999, CameraFrames.micros(b));
    }

    /** 200 frames a second with a flick between two ticks: the track follows the frames, not the ticks. */
    @Test
    void theTrackFollowsEveryFrame() throws Exception {
        Path f = dir.resolve("frames.breezerec");
        byte[] buf = new byte[CameraFrames.SIZE];
        try (RecWriter w = new RecWriter(f, meta())) {
            for (int frame = 0; frame <= 2000; frame++) { // 10 s at 200 fps, 5 ms apart
                long us = frame * 5_000L;
                // A steady turn, and a flick out to +90 and back inside 100 ms at 4 s.
                float yaw = frame * 0.5f;
                if (us >= 4_000_000 && us < 4_050_000) yaw += (us - 4_000_000) / 50_000f * 90;
                else if (us >= 4_050_000 && us < 4_100_000) yaw += (4_100_000 - us) / 50_000f * 90;
                CameraFrames.encode(buf, (int) (us % 1000), yaw, -10);
                w.record(RecFormat.Kind.CAMERA, us / 1000, buf);
                if (frame % 10 == 0) w.record(RecFormat.Kind.SELF, us / 1000, self(frame * 0.5f, -10));
            }
            w.close(10_000);
        }
        try (RecReader r = new RecReader(f)) {
            CameraTrack t = new CameraTrack(r);
            float[] out = new float[2];
            assertTrue(t.at(1000, out));
            assertTrue(t.fromFrames());
            assertEquals(100f, out[0], 0.01f);
            assertEquals(-10f, out[1], 0.01f);
            // Between frames: interpolated (2.5 ms is half a frame).
            assertTrue(t.at(1002.5, out));
            assertEquals(100.25f, out[0], 0.01f);
            // The top of the flick, which the ticks (every 50 ms) never saw.
            assertTrue(t.at(4050, out));
            assertEquals(405f + 90f, out[0], 0.01f);
            // Back again: a seek backwards works too.
            assertTrue(t.at(500, out));
            assertEquals(50f, out[0], 0.01f);
            assertTrue(t.at(9999.9, out));
            assertTrue(t.at(20_000, out));
            assertEquals(1000f, out[0], 0.01f);
        }
    }

    /** A recording made before frames were recorded: the ticks are interpolated instead. */
    @Test
    void olderRecordingsTurnBetweenTicks() throws Exception {
        Path f = dir.resolve("ticks.breezerec");
        try (RecWriter w = new RecWriter(f, meta())) {
            for (int tick = 0; tick <= 100; tick++) w.record(RecFormat.Kind.SELF, tick * 50L, self(tick * 10f, 5));
            w.close(5_000);
        }
        try (RecReader r = new RecReader(f)) {
            assertEquals(0, r.meta().frames);
            assertEquals(60, ExportSettings.resolveFps(ExportSettings.AS_RECORDED, r.meta()));
            CameraTrack t = new CameraTrack(r);
            float[] out = new float[2];
            assertTrue(t.at(1025, out));
            assertFalse(t.fromFrames());
            assertEquals(205f, out[0], 0.01f);
            assertEquals(5f, out[1], 0.01f);
        }
    }

    @Test
    void aLongGapHoldsTheEarlierFrame() throws Exception {
        Path f = dir.resolve("gap.breezerec");
        byte[] buf = new byte[CameraFrames.SIZE];
        try (RecWriter w = new RecWriter(f, meta())) {
            CameraFrames.encode(buf, 0, 10, 0);
            w.record(RecFormat.Kind.CAMERA, 100, buf);
            CameraFrames.encode(buf, 0, 90, 0);
            w.record(RecFormat.Kind.CAMERA, 1100, buf);
            w.close(1200);
        }
        try (RecReader r = new RecReader(f)) {
            float[] out = new float[2];
            CameraTrack t = new CameraTrack(r);
            assertTrue(t.at(600, out));
            assertEquals(10f, out[0], 0.01f);
            assertTrue(t.at(1100, out));
            assertEquals(90f, out[0], 0.01f);
        }
    }

    @Test
    void emptyRecordingHasNoCamera() throws Exception {
        Path f = dir.resolve("empty.breezerec");
        try (RecWriter w = new RecWriter(f, meta())) {
            w.close(0);
        }
        try (RecReader r = new RecReader(f)) {
            assertFalse(new CameraTrack(r).at(0, new float[2]));
        }
    }

    @Test
    void framesRatesAndAsRecorded() {
        RecMeta m = new RecMeta();
        m.fps = 197.6;
        assertEquals(198, ExportSettings.resolveFps(ExportSettings.AS_RECORDED, m));
        m.fps = 1450;
        assertEquals(360, ExportSettings.resolveFps(ExportSettings.AS_RECORDED, m));
        m.fps = 3;
        assertEquals(10, ExportSettings.resolveFps(ExportSettings.AS_RECORDED, m));
        assertEquals(240, ExportSettings.resolveFps(240, m));
        assertEquals("As recorded", ExportSettings.fpsLabel(0));
        assertEquals("165 fps", ExportSettings.fpsLabel(165));
        // The cycle: 30, 60, 120, 144, 165, 240, As recorded, 30.
        ExportSettings s = new ExportSettings();
        s.fps = 30;
        int[] seen = new int[7];
        for (int i = 0; i < 7; i++) {
            seen[i] = s.fps;
            s.fps = nextRate(s.fps);
        }
        assertEquals(List.of(30, 60, 120, 144, 165, 240, 0), java.util.Arrays.stream(seen).boxed().toList());
        assertEquals(30, s.fps);
    }

    private static int nextRate(int v) {
        int[] r = ExportSettings.RATES;
        for (int i = 0; i < r.length; i++) if (r[i] == v) return r[(i + 1) % r.length];
        return r[0];
    }

    @Test
    void readsEveryOfferedRateBack(@TempDir Path game) throws Exception {
        for (int rate : ExportSettings.RATES) {
            java.nio.file.Files.createDirectories(game.resolve("config"));
            java.nio.file.Files.writeString(game.resolve("config/breeze_export.properties"), "fps=" + rate + "\n");
            assertEquals(rate, ExportSettings.read(game.resolve("config/breeze_export.properties")).fps);
        }
    }

    @Test
    void levelsFitTheSizeAndRate() {
        assertEquals(31, VideoEncoder.level(1280, 720, 30));
        assertEquals(40, VideoEncoder.level(1920, 1080, 30));
        assertEquals(42, VideoEncoder.level(1920, 1080, 60));
        assertEquals(51, VideoEncoder.level(1920, 1080, 120));
        assertEquals(52, VideoEncoder.level(1920, 1080, 240));
        assertEquals(51, VideoEncoder.level(3840, 2160, 30));
        assertEquals(52, VideoEncoder.level(3840, 2160, 60));
        assertEquals(60, VideoEncoder.level(3840, 2160, 120));
        assertEquals(61, VideoEncoder.level(3840, 2160, 240));
        assertEquals(62, VideoEncoder.level(3840, 2160, 360));
        assertEquals(52, VideoEncoder.level(2560, 1440, 144));
        assertEquals("5.2", VideoEncoder.levelName(52));
        VideoEncoder.Codec nvenc = new VideoEncoder.Codec("h264_nvenc", "NVIDIA NVENC", true);
        String cmd = String.join(" ", VideoEncoder.command("ffmpeg", nvenc, VideoEncoder.Quality.HIGH, 3840, 2160, 240, Path.of("o.mp4"), false));
        assertTrue(cmd.contains("-level:v 61"), cmd);
        assertTrue(cmd.contains("-r 240"), cmd);
        String vt = String.join(" ", VideoEncoder.command("ffmpeg", new VideoEncoder.Codec("h264_videotoolbox", "VT", true),
                VideoEncoder.Quality.HIGH, 3840, 2160, 240, Path.of("o.mp4"), false));
        assertFalse(vt.contains("-level"), vt);
    }

    /** With ffmpeg here: x264 takes 4K at 240 fps with its level, and a refused encoder falls back to x264. */
    @Test
    void x264TakesHighRatesAndRefusalsFallBack() throws Exception {
        Assumptions.assumeTrue(onPath("ffmpeg"), "no ffmpeg here");
        VideoEncoder.Codec x = VideoEncoder.confirm("ffmpeg", VideoEncoder.X264, dir, 3840, 2160, 240);
        assertEquals(VideoEncoder.X264, x);
        VideoEncoder.Codec missing = new VideoEncoder.Codec("h264_breeze_missing", "missing", true);
        assertEquals(VideoEncoder.X264, VideoEncoder.confirm("ffmpeg", missing, dir, 1920, 1080, 240));
        assertNotNull(VideoEncoder.confirm("ffmpeg", VideoEncoder.X264, dir, 1280, 720, 30));
    }

    private static boolean onPath(String program) {
        try {
            Process p = new ProcessBuilder(program, "-version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
