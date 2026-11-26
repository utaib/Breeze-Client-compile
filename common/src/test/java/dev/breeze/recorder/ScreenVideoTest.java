package dev.breeze.recorder;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** The screen video: frame pacing to an even rate, preferences, and the live encoder with a real ffmpeg when there is one. */
class ScreenVideoTest {

    private static final long MS = 1_000_000L;

    /** A game at an uneven 54 to 70 fps for 10 s makes a 60 fps video of 10 s, each tick at most half a game frame off. */
    @Test
    void unevenGameMakesEvenVideo() {
        FramePacer p = new FramePacer(60);
        Random r = new Random(7);
        long t = 1_000 * MS, total = 0;
        long end = t + 10_000 * MS;
        long worstGap = 0;
        total += p.add(t);
        while (t < end) {
            long gap = (long) (1e9 / (54 + r.nextInt(17)));
            worstGap = Math.max(worstGap, gap);
            t += gap;
            total += p.add(t);
        }
        total += p.finish();
        assertEquals(total, p.ticks());
        long expected = Math.round((t - 1_000 * MS) / 1e9 * 60) + 1;
        assertTrue(Math.abs(total - expected) <= 1, "ticks " + total + " vs " + expected);
        assertTrue(p.errorWorstMs() <= worstGap / 2.0 / MS + 0.01, p.summary());
        assertTrue(p.gapAvgMs() > 14 && p.gapAvgMs() < 19, p.summary());
    }

    /** A game exactly at the video's rate puts every frame in once. */
    @Test
    void steadyGameIsOneToOne() {
        FramePacer p = new FramePacer(60);
        long tick = 1_000_000_000L / 60;
        long t = 0, total = 0;
        int ones = 0;
        for (int i = 0; i < 600; i++) {
            int n = p.add(t);
            if (i > 0 && n == 1) ones++;
            total += n;
            t += tick;
        }
        total += p.finish();
        assertEquals(599, ones);
        assertEquals(600, total);
        assertEquals(0, p.repeated());
        assertEquals(0, p.skipped());
    }

    /** A 30 fps game in a 60 fps video shows each frame twice; a 120 fps game in a 60 fps video drops every other frame. */
    @Test
    void slowAndFastGames() {
        FramePacer slow = new FramePacer(60);
        long t = 0, total = 0;
        for (int i = 0; i < 300; i++) {
            total += slow.add(t);
            t += 1_000_000_000L / 30;
        }
        total += slow.finish();
        assertTrue(Math.abs(total - 600) <= 1, "slow " + total);
        FramePacer fast = new FramePacer(60);
        t = 0;
        total = 0;
        for (int i = 0; i < 1200; i++) {
            total += fast.add(t);
            t += 1_000_000_000L / 120;
        }
        total += fast.finish();
        assertTrue(Math.abs(total - 600) <= 1, "fast " + total);
        assertTrue(fast.skipped() >= 590, fast.summary());
    }

    /** A stall (a 300 ms frame) repeats the frame before for its ticks; the length stays real. */
    @Test
    void stallRepeats() {
        FramePacer p = new FramePacer(60);
        long tick = 1_000_000_000L / 60, t = 0, total = 0;
        for (int i = 0; i < 60; i++) {
            total += p.add(t);
            t += tick;
        }
        t += 300 * MS;
        total += p.add(t);
        total += p.finish();
        assertTrue(Math.abs(total - (60 + 18)) <= 1, "total " + total);
        assertTrue(p.repeated() >= 16, p.summary());
        assertTrue(p.gapWorstMs() >= 300, p.summary());
    }

    @Test
    void preferences(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path f = dir.resolve("p.properties");
        CaptureSettings s = CaptureSettings.read(f);
        assertFalse(s.configured);
        assertTrue(s.keepReplay);
        assertEquals(60, s.fps);
        assertEquals(0, s.size);
        Files.writeString(f, "configured=true\nkeepReplay=false\nfps=120\nsize=720\nquality=best\nhardware=false\n");
        s = CaptureSettings.read(f);
        assertTrue(s.configured);
        assertFalse(s.keepReplay);
        assertEquals(120, s.fps);
        assertEquals(720, s.size);
        assertEquals(VideoEncoder.Quality.BEST, s.quality);
        assertFalse(s.hardware);
        Files.writeString(f, "fps=59\nsize=333\n");
        s = CaptureSettings.read(f);
        assertEquals(60, s.fps);
        assertEquals(0, s.size);
        assertArrayEquals(new int[]{1280, 720}, CaptureSettings.videoSize(720, 1920, 1080));
        assertArrayEquals(new int[]{854, 480}, CaptureSettings.videoSize(1080, 855, 481));
        assertArrayEquals(new int[]{1920, 1080}, CaptureSettings.videoSize(0, 1920, 1080));
    }

    @Test
    void liveCommandIsFragmentedAndFast() {
        List<String> c = VideoEncoder.liveCommand("ffmpeg", VideoEncoder.X264, VideoEncoder.Quality.HIGH, 1280, 720, 60, Path.of("v.mp4"));
        String s = String.join(" ", c);
        assertTrue(s.contains("+frag_keyframe+empty_moov"), s);
        assertTrue(s.contains("-preset veryfast"), s);
        assertTrue(s.contains("-s 1280x720"), s);
        assertTrue(s.contains("-g 60"), s);
        assertFalse(s.contains("vflip"), s);
    }

    /**
     * A real ffmpeg (when the machine has one): 3 s of uneven frames at a
     * 60 fps video makes a playable MP4 of 180 frames, also when the encoder
     * is fed faster than it takes them.
     */
    @Test
    void liveEncoderWithFfmpeg(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        if (!Ffmpeg.runs("ffmpeg") || !Ffmpeg.runs("ffprobe")) return;
        int w = 320, h = 180, fps = 60;
        Path out = dir.resolve("screen.mp4");
        LiveEncoder enc = new LiveEncoder(VideoEncoder.liveCommand("ffmpeg", VideoEncoder.X264, VideoEncoder.Quality.STANDARD, w, h, fps, out), w, h, 2);
        FramePacer pacer = new FramePacer(fps);
        Random r = new Random(3);
        long t = 0;
        ByteBuffer held = null;
        int frame = 0;
        while (t < 3_000 * MS) {
            ByteBuffer b = enc.take();
            int n = pacer.add(t);
            if (held != null) enc.submit(held, n);
            else if (n > 0) enc.repeatLast(n);
            if (b != null) {
                byte v = (byte) (frame++ * 3);
                for (int i = 0; i < w * h * 4; i++) b.put(i, v);
            }
            held = b;
            t += (long) (1e9 / (54 + r.nextInt(17)));
        }
        int n = pacer.finish();
        if (held != null) enc.submit(held, n);
        else enc.repeatLast(n);
        assertNull(enc.finish());
        assertEquals(pacer.ticks(), enc.written());
        Process p = new ProcessBuilder("ffprobe", "-v", "error", "-count_frames", "-select_streams", "v:0",
                "-show_entries", "stream=nb_read_frames,width,height,r_frame_rate", "-of", "csv=p=0", out.toString())
                .redirectErrorStream(true).start();
        String info = new String(p.getInputStream().readAllBytes()).trim();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        assertTrue(info.startsWith("320,180,60/1,"), info);
        long frames = Long.parseLong(info.substring(info.lastIndexOf(',') + 1));
        assertEquals(pacer.ticks(), frames, info);
        assertTrue(Math.abs(frames - 180) <= 2, info);
    }
}
