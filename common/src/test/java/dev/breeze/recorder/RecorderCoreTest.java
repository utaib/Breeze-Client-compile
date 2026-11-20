package dev.breeze.recorder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecorderCoreTest {

    @TempDir
    Path dir;

    private static RecMeta meta() {
        RecMeta m = new RecMeta();
        m.minecraft = "1.21.11";
        m.protocol = 774;
        m.loader = "fabric";
        m.breeze = "2.30.0";
        m.world = "New World";
        m.player = "BreezeDev";
        m.startedAt = 1_763_500_000_000L;
        return m;
    }

    /** A packet-like payload: some repetition (as real packets have) and some noise. */
    private static byte[] payload(Random r, int i) {
        byte[] b = new byte[20 + r.nextInt(400)];
        for (int k = 0; k < b.length; k++) b[k] = (byte) (k % 7 == 0 ? r.nextInt() : i);
        return b;
    }

    @Test
    void writesAndReadsBackEveryRecordInOrder() throws Exception {
        Path f = dir.resolve("a" + RecFormat.EXTENSION);
        Random r = new Random(1);
        List<byte[]> sent = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        try (RecWriter w = new RecWriter(f, meta())) {
            long t = 0;
            for (int i = 0; i < 20_000; i++) {
                t += r.nextInt(3);
                byte[] b = payload(r, i);
                assertTrue(w.record(i % 3 == 0 ? RecFormat.Kind.SELF : RecFormat.Kind.PACKET, t, b));
                sent.add(b);
                times.add(t);
            }
            w.close(t);
            assertNull(w.failure());
            assertTrue(w.bytesOnDisk() < w.rawBytes(), "compressed: " + w.bytesOnDisk() + " of " + w.rawBytes());
        }
        try (RecReader rd = new RecReader(f)) {
            assertTrue(rd.complete());
            assertEquals("1.21.11", rd.meta().minecraft);
            assertTrue(rd.meta().finished);
            assertEquals(times.get(times.size() - 1).longValue(), rd.durationMs());
            assertTrue(rd.chunks().size() > 1, "several chunks: " + rd.chunks().size());
            int i = 0;
            for (RecReader.ChunkRef c : rd.chunks()) {
                for (RecReader.Rec rec : rd.readChunk(c)) {
                    assertEquals(times.get(i).longValue(), rec.timeMs(), "time of record " + i);
                    assertEquals(i % 3 == 0 ? RecFormat.Kind.SELF : RecFormat.Kind.PACKET, rec.kind());
                    assertArrayEquals(sent.get(i), rec.data(), "bytes of record " + i);
                    i++;
                }
            }
            assertEquals(sent.size(), i);
        }
    }

    @Test
    void seeksToTheChunkHoldingAMoment() throws Exception {
        Path f = dir.resolve("seek" + RecFormat.EXTENSION);
        try (RecWriter w = new RecWriter(f, meta())) {
            // One record every 10 ms for 60 s: a chunk every 4 s.
            for (int t = 0; t <= 60_000; t += 10) w.record(RecFormat.Kind.PACKET, t, new byte[]{(byte) t, (byte) (t >> 8)});
            w.close(60_000);
        }
        try (RecReader rd = new RecReader(f)) {
            int c = rd.chunkAt(30_005);
            RecReader.ChunkRef ref = rd.chunks().get(c);
            assertTrue(ref.firstMs() <= 30_005 && ref.lastMs() >= 30_000, ref.toString());
            assertEquals(0, rd.chunkAt(0));
            assertEquals(rd.chunks().size() - 1, rd.chunkAt(10_000_000));
        }
    }

    @Test
    void aRecordingCutShortIsStillReadable() throws Exception {
        Path f = dir.resolve("crash" + RecFormat.EXTENSION);
        RecWriter w = new RecWriter(f, meta());
        for (int t = 0; t < 20_000; t += 5) w.record(RecFormat.Kind.PACKET, t, new byte[]{1, 2, 3, (byte) t});
        w.flush();
        // Wait until the writer has put the chunks on disk, then "crash": no close.
        long until = System.currentTimeMillis() + 5_000;
        while (w.pendingBytes() > 0 && System.currentTimeMillis() < until) Thread.sleep(20);
        // Half a chunk more, as a crash in the middle of a write leaves.
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "rw")) {
            raf.seek(raf.length());
            raf.write(new byte[]{RecFormat.CHUNK, 0, 0, 0});
        }
        try (RecReader rd = new RecReader(f)) {
            assertFalse(rd.complete());
            assertFalse(rd.meta().finished);
            assertTrue(rd.chunks().size() >= 4, "chunks found by scanning: " + rd.chunks().size());
            assertTrue(rd.durationMs() >= 19_000, "length from the last chunk: " + rd.durationMs());
            int n = 0;
            for (RecReader.ChunkRef c : rd.chunks()) n += rd.readChunk(c).size();
            assertEquals(4_000, n);
        }
        w.close(20_000);
    }

    @Test
    void refusesFilesThatAreNotRecordings() throws Exception {
        Path f = dir.resolve("x" + RecFormat.EXTENSION);
        Files.write(f, new byte[100]);
        assertThrows(IOException.class, () -> new RecReader(f));
        assertThrows(IOException.class, () -> RecReader.readMeta(f));
    }

    @Test
    void metaReadsWithoutTheBody() throws Exception {
        Path f = dir.resolve("m" + RecFormat.EXTENSION);
        try (RecWriter w = new RecWriter(f, meta())) {
            w.record(RecFormat.Kind.MARKER, 5, new byte[0]);
            w.close(1234);
        }
        RecMeta m = RecReader.readMeta(f);
        assertEquals(1234, m.durationMs);
        assertEquals("BreezeDev", m.player);
        assertTrue(m.finished);
    }

    @Test
    void writingCostsLittleOnTheCallingThread() throws Exception {
        // The game thread only copies bytes: 100k records of 200 bytes (20 MB)
        // must be appended in well under a second even on a slow build machine.
        Path f = dir.resolve("speed" + RecFormat.EXTENSION);
        byte[] b = new byte[200];
        try (RecWriter w = new RecWriter(f, meta())) {
            long start = System.nanoTime();
            for (int i = 0; i < 100_000; i++) w.record(RecFormat.Kind.PACKET, i / 20, b);
            long ns = System.nanoTime() - start;
            assertTrue(ns < 1_000_000_000L, "appending took " + ns / 1_000_000 + " ms");
            w.close(5_000);
            assertNull(w.failure());
        }
    }

    @Test
    void timelinePlaysPausesChangesSpeedAndSeeks() {
        Timeline t = new Timeline(10_000);
        assertFalse(t.playing());
        t.advance(500);
        assertEquals(0, t.positionMs());
        t.play();
        t.advance(1000);
        assertEquals(1000, t.positionMs());
        t.changeSpeed(1);
        assertEquals(2.0, t.speed());
        t.advance(1000);
        assertEquals(3000, t.positionMs());
        t.setSpeed(0.3);
        assertEquals(0.25, t.speed());
        t.seek(-5);
        assertEquals(0, t.positionMs());
        t.seek(99_999);
        assertTrue(t.atEnd());
        t.play();
        assertEquals(0, t.positionMs(), "play at the end starts again");
        t.setSpeed(8);
        t.advance(5_000);
        assertTrue(t.atEnd());
        assertFalse(t.playing(), "stops at the end");
        Timeline e = new Timeline(1000);
        int n = 0;
        while (!e.atEnd()) {
            e.stepFrame(60);
            n++;
        }
        assertEquals(60, n);
        assertEquals(60, Timeline.frames(1000, 60));
        assertEquals("1:05", Timeline.clock(65_000));
        assertEquals("1:02:03", Timeline.clock(3_723_000));
    }

    @Test
    void ffmpegCommands() {
        VideoEncoder.Codec x264 = new VideoEncoder.Codec("libx264", "x264", false);
        List<String> cmd = VideoEncoder.command("ffmpeg", x264, VideoEncoder.Quality.HIGH, 1920, 1080, 60, dir.resolve("o.mp4"));
        String s = String.join(" ", cmd);
        assertTrue(s.contains("-f rawvideo -pix_fmt rgba -s 1920x1080 -framerate 60 -i -"), s);
        assertTrue(s.contains("-vf vflip"), s);
        assertTrue(s.contains("-crf 18"), s);
        assertTrue(s.endsWith(dir.resolve("o.mp4").toAbsolutePath().toString()), s);
        assertEquals("h264_videotoolbox", VideoEncoder.candidates("mac").get(0).name());
        assertEquals("h264_nvenc", VideoEncoder.candidates("windows").get(0).name());
        assertEquals("libx264", VideoEncoder.candidates("linux").get(VideoEncoder.candidates("linux").size() - 1).name());
        String vaapi = String.join(" ", VideoEncoder.command("ffmpeg", new VideoEncoder.Codec("h264_vaapi", "VAAPI", true),
                VideoEncoder.Quality.STANDARD, 1280, 720, 30, dir.resolve("v.mp4")));
        assertTrue(vaapi.contains("-vf vflip,crop=trunc(iw/2)*2:trunc(ih/2)*2,format=nv12,hwupload"), vaapi);
        String top = String.join(" ", VideoEncoder.command("ffmpeg", x264, VideoEncoder.Quality.STANDARD, 1279, 719, 60, dir.resolve("t.mp4"), false));
        assertTrue(top.contains("-vf crop=trunc(iw/2)*2:trunc(ih/2)*2 "), top);
        assertEquals("mac", VideoEncoder.osKey("Mac OS X"));
    }

    /** With an ffmpeg on the machine: a real 2 second video, checked with ffprobe. */
    @Test
    void encodesARealVideoWhenFfmpegIsThere() throws Exception {
        Assumptions.assumeTrue(onPath("ffmpeg") && onPath("ffprobe"), "no ffmpeg here");
        VideoEncoder.Codec c = VideoEncoder.pick("ffmpeg", "linux", dir);
        assertNotNull(c, "x264 at least works");
        Path out = dir.resolve("test.mp4");
        int w = 320, h = 180, fps = 30;
        try (VideoEncoder e = new VideoEncoder(VideoEncoder.command("ffmpeg", c, VideoEncoder.Quality.STANDARD, w, h, fps, out), w, h)) {
            for (int i = 0; i < 60; i++) {
                byte[] f = e.buffer();
                for (int p = 0; p < f.length; p += 4) {
                    f[p] = (byte) (i * 4);
                    f[p + 1] = (byte) 100;
                    f[p + 2] = (byte) (255 - i * 4);
                    f[p + 3] = (byte) 255;
                }
                assertTrue(e.frame(f));
            }
            assertNull(e.finish());
        }
        Process p = new ProcessBuilder("ffprobe", "-v", "error", "-select_streams", "v:0", "-count_frames",
                "-show_entries", "stream=width,height,nb_read_frames,r_frame_rate", "-of", "csv=p=0", out.toString())
                .redirectErrorStream(true).start();
        String probe = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        assertEquals("320,180,30/1,60", probe);
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
