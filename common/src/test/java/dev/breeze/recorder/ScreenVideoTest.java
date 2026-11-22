package dev.breeze.recorder;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ScreenVideoTest {

    private static final long MS = 1_000_000L;

    /** Feeds frames at the given times through a pacer; returns the slots each used frame starts at (repeats included). */
    private static FramePacer feed(FramePacer p, long[] times) {
        for (long t : times) {
            if (p.plan(t) >= 0) p.commit(t);
        }
        return p;
    }

    @Test
    void steadyGameAtVideoRateUsesEveryFrame() {
        long[] t = new long[120];
        for (int i = 0; i < t.length; i++) t[i] = 1_000 * MS + Math.round(i * 1e9 / 60);
        FramePacer p = feed(new FramePacer(60), t);
        assertEquals(120, p.used());
        assertEquals(0, p.skipped());
        assertEquals(0, p.dupSlots());
        assertEquals(120, p.slots());
        assertTrue(p.motionJitterMs() < 0.1, p.summary());
    }

    @Test
    void unevenGameKeepsTheRecordingsLength() {
        // 54 to 70 fps, as on the owner's laptop, for 10 seconds.
        Random r = new Random(1);
        long t = 0;
        long[] times = new long[700];
        int n = 0;
        while (t < 10_000 * MS && n < times.length) {
            times[n++] = t;
            t += Math.round(1e9 / (54 + r.nextInt(17)));
        }
        long end = times[n - 1] + 15 * MS;
        FramePacer p = feed(new FramePacer(60), Arrays.copyOf(times, n));
        long total = p.slots() + p.trailing(end);
        assertEquals(Math.round((end - times[0]) / (1e9 / 60)), total);
        // Every frame shown within half a slot of its real time.
        assertTrue(p.summary().contains("slotErrorMaxMs="), p.summary());
        double half = 1000.0 / 60 / 2 + 0.01;
        String max = p.summary().replaceAll(".*slotErrorMaxMs=([0-9.]+).*", "$1");
        assertTrue(Double.parseDouble(max) <= half, p.summary());
        assertEquals(p.used() + p.dupSlots(), p.slots());
    }

    @Test
    void fasterGameSkipsFramesItDoesNotNeed() {
        long[] t = new long[240];
        for (int i = 0; i < t.length; i++) t[i] = Math.round(i * 1e9 / 120);
        FramePacer p = feed(new FramePacer(60), t);
        // Half of them, give or take the last (a frame on a slot's edge rounds up).
        assertTrue(Math.abs(p.used() - 120) <= 1, p.summary());
        assertEquals(240, p.used() + p.skipped());
        assertEquals(0, p.dupSlots());
    }

    @Test
    void aStallRepeatsTheFrameBefore() {
        FramePacer p = new FramePacer(30);
        assertEquals(0, p.plan(0));
        p.commit(0);
        long later = 1_000 * MS; // a one-second hitch
        int dups = p.plan(later);
        assertEquals(29, dups);
        p.commit(later);
        assertEquals(31, p.slots());
        assertEquals(29, p.dupSlots());
    }

    @Test
    void droppedFrameIsNotCommitted() {
        FramePacer p = new FramePacer(60);
        p.plan(0);
        p.commit(0);
        long t1 = Math.round(1e9 / 60);
        assertEquals(0, p.plan(t1));
        p.dropped();
        long t2 = Math.round(2e9 / 60);
        assertEquals(1, p.plan(t2)); // slot 1 shows frame 0 again
        p.commit(t2);
        assertEquals(1, p.droppedFrames());
        assertEquals(3, p.slots());
    }

    @Test
    void prefsSaveAndRead(@TempDir Path dir) {
        RecorderPrefs p = RecorderPrefs.get(dir);
        assertFalse(p.chosen);
        assertTrue(p.keepReplay);
        assertEquals(60, p.fps);
        p.chosen = true;
        p.keepReplay = false;
        p.nextFps(); // 120
        p.mic = true;
        p.micDevice = "Headset (USB)";
        p.save();
        RecorderPrefs back = RecorderPrefs.read(dir.resolve("config").resolve("breeze_recorder.properties"));
        assertTrue(back.chosen);
        assertFalse(back.keepReplay);
        assertEquals(120, back.fps);
        assertTrue(back.mic);
        assertEquals("Headset (USB)", back.micDevice);
    }

    @Test
    void videoSizeNeverGrows() {
        assertArrayEquals(new int[]{1728, 972}, RecorderPrefs.videoSize(0, 1728, 972));
        assertArrayEquals(new int[]{1728, 972}, RecorderPrefs.videoSize(1080, 1728, 972));
        assertArrayEquals(new int[]{1280, 720}, RecorderPrefs.videoSize(720, 1920, 1080));
        assertArrayEquals(new int[]{854, 480}, RecorderPrefs.videoSize(0, 855, 481));
    }

    @Test
    void liveCommandIsFragmentedNv12() {
        List<String> c = LiveVideo.command("ffmpeg", VideoEncoder.X264, VideoEncoder.Quality.HIGH, 1920, 1080, 60, Path.of("out.mp4"));
        String s = String.join(" ", c);
        assertTrue(s.contains("-pix_fmt nv12 -s 1920x1080 -framerate 60 -i -"), s);
        assertTrue(s.contains("+frag_keyframe+empty_moov"), s);
        assertTrue(s.contains("-preset veryfast"), s);
        assertTrue(s.contains("-g 120"), s);
        assertEquals(1920 * 1080 * 3 / 2, LiveVideo.nv12Bytes(1920, 1080));
    }

    /** A real ffmpeg run: frames with repeats make a video of exactly the slots asked for. */
    @Test
    void encodesTheRightFrameCount(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(Ffmpeg.runs("ffmpeg") && Ffmpeg.runs("ffprobe"), "ffmpeg not installed here");
        int w = 64, h = 36, fps = 30;
        Path out = dir.resolve("v.mp4");
        LiveVideo v = new LiveVideo(LiveVideo.command("ffmpeg", VideoEncoder.X264, VideoEncoder.Quality.STANDARD, w, h, fps, out), LiveVideo.nv12Bytes(w, h));
        int sent = 0, slots = 0;
        for (int i = 0; i < 20; i++) {
            byte shade = (byte) (16 + i * 10);
            int rep = i % 5 == 4 ? 2 : 0;
            LiveVideo.Source src = new LiveVideo.Source() {
                public void copyTo(byte[] dst) { Arrays.fill(dst, 0, w * h, shade); Arrays.fill(dst, w * h, dst.length, (byte) 128); }
                public void release() {}
            };
            while (!v.offer(src, rep)) Thread.sleep(2);
            sent++;
            slots += rep + 1;
        }
        String err = v.finish(3);
        assertNull(err);
        slots += 3;
        Process p = new ProcessBuilder("ffprobe", "-v", "error", "-count_frames", "-select_streams", "v:0",
                "-show_entries", "stream=nb_read_frames", "-of", "csv=p=0", out.toString()).redirectErrorStream(true).start();
        String n = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertTrue(p.waitFor(20, TimeUnit.SECONDS));
        assertEquals(String.valueOf(slots), n);
        assertEquals(slots, v.framesWritten());
        assertEquals(20, sent);
        assertTrue(Files.size(out) > 0);
    }
}
