package dev.breeze.recorder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Round 2's sound: tracks kept in time, the launcher's files, and the join with a real ffmpeg when there is one. */
class SoundTest {

    private static final long T0 = 1_700_000_000_000_000L;

    /** Pieces of 20 ms with a 500 ms hole: the hole is filled, so the file keeps the time. */
    @Test
    void holesAreFilledWithSilence(@TempDir Path dir) throws Exception {
        SoundTrack t = new SoundTrack(dir.resolve("a.pcm"), dir.resolve("a.info"), 48_000, 2);
        byte[] piece = new byte[960 * 4];
        long us = T0;
        for (int i = 0; i < 50; i++) t.write(piece, 0, piece.length, us += 20_000);
        us += 500_000;
        for (int i = 0; i < 50; i++) t.write(piece, 0, piece.length, us += 20_000);
        t.finish(us + 300_000);
        long expected = (us + 300_000 - t.startUs()) * 48_000 / 1_000_000;
        assertTrue(Math.abs(t.frames() - expected) <= 48_000 * SoundTrack.GAP_MS / 1000, t.frames() + " vs " + expected);
        assertTrue(t.filled() > 48_000 * 0.7, "filled " + t.filled());
        assertEquals(t.frames() * 4, Files.size(dir.resolve("a.pcm")));
        SoundTrack.Info i = SoundTrack.Info.read(dir.resolve("a.info"));
        assertNotNull(i);
        assertTrue(i.done());
        assertEquals(T0, i.startUs());
        assertEquals(t.frames(), i.frames());
    }

    /** Small jitter is not a hole: nothing is put in. */
    @Test
    void jitterIsNotAHole(@TempDir Path dir) throws Exception {
        SoundTrack t = new SoundTrack(dir.resolve("a.pcm"), dir.resolve("a.info"), 48_000, 1);
        byte[] piece = new byte[960 * 2];
        long us = T0;
        for (int i = 0; i < 100; i++) t.write(piece, 0, piece.length, us += (i % 2 == 0 ? 35_000 : 5_000));
        assertEquals(0, t.filled());
    }

    /** The launcher's ready file counts only for this process. */
    @Test
    void readyNeedsOurProcess(@TempDir Path dir) throws Exception {
        assertFalse(SoundLink.ready(dir, 42).available());
        Files.createDirectories(SoundLink.dir(dir));
        Files.writeString(SoundLink.dir(dir).resolve("ready"), "pid=41\ngame=1\n");
        assertFalse(SoundLink.ready(dir, 42).available());
        Files.writeString(SoundLink.dir(dir).resolve("ready"), "pid=42\ngame=0\nreason=Game sound needs Windows.\n");
        SoundLink.Ready r = SoundLink.ready(dir, 42);
        assertFalse(r.available());
        assertEquals("Game sound needs Windows.", r.reason());
        Files.writeString(SoundLink.dir(dir).resolve("ready"), "pid=42\ngame=1\n");
        assertTrue(SoundLink.ready(dir, 42).available());
        SoundLink.askStart(dir, "2026-11-26_10.00.00");
        assertEquals("2026-11-26_10.00.00", SoundLink.requestName(SoundLink.dir(dir).resolve("start")));
        assertThrows(java.io.IOException.class, () -> SoundLink.askStart(dir, "../x"));
        assertFalse(SoundLink.validName("a/b"));
        assertFalse(SoundLink.validName(".."));
    }

    @Test
    void alignmentFilter() {
        SoundTrack.Info late = new SoundTrack.Info(48_000, 2, T0 + 250_000, 0, "done", null);
        assertEquals("adelay=delays=12000S:all=1,", SoundMux.align(late, T0));
        SoundTrack.Info early = new SoundTrack.Info(48_000, 2, T0 - 300_000, 0, "done", null);
        assertEquals("atrim=start=0.300000,asetpts=PTS-STARTPTS,", SoundMux.align(early, T0));
        assertEquals("", SoundMux.align(new SoundTrack.Info(44_100, 1, T0, 0, "done", null), T0));
    }

    /**
     * A 3 s video and two tracks, one started 300 ms before its first frame
     * and one 250 ms after, with a tone at 1.0 s and 2.0 s of the video: the
     * join has two audio tracks named Game and Microphone, each as long as
     * the video, with the tones where they belong, and the video unchanged.
     */
    @Test
    void joinKeepsTracksInTime(@TempDir Path dir) throws Exception {
        String ff = Ffmpeg.find();
        if (ff == null) return;
        Path video = dir.resolve("v.mp4");
        Process p = new ProcessBuilder(ff, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "color=c=gray:s=64x64:r=30",
                "-t", "3", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-movflags", "+frag_keyframe+empty_moov+default_base_moof",
                video.toString()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0);
        long v0 = T0;
        tone(dir, "game", v0 - 300_000, v0 + 1_000_000, 440, 2, 48_000, 3_600_000);
        tone(dir, "mic", v0 + 250_000, v0 + 2_000_000, 1000, 1, 44_100, 2_900_000);
        List<SoundMux.Input> in = List.of(
                new SoundMux.Input(dir.resolve("game.pcm"), SoundTrack.Info.read(dir.resolve("game.info")), "Game"),
                new SoundMux.Input(dir.resolve("mic.pcm"), SoundTrack.Info.read(dir.resolve("mic.info")), "Microphone"));
        assertNull(SoundMux.run(ff, video, in, v0, 3.0));
        assertEquals(List.of("Game", "Microphone"), SoundProbe.tracks(ff, video));
        double[] g = SoundProbe.onsetAndLengthMs(ff, video, 0), m = SoundProbe.onsetAndLengthMs(ff, video, 1);
        assertEquals(1000, g[0], 15, "game tone at " + g[0]);
        assertEquals(2000, m[0], 15, "microphone tone at " + m[0]);
        assertEquals(3000, g[1], 40, "game length " + g[1]);
        assertEquals(3000, m[1], 40, "microphone length " + m[1]);
        assertFalse(Files.exists(dir.resolve("v.sound.mp4")));
    }

    /** A track of silence with a 200 ms tone at beepUs, written as a source would write it (20 ms pieces). */
    private static void tone(Path dir, String name, long startUs, long beepUs, double hz, int ch, int rate, long lengthUs) throws Exception {
        SoundTrack t = new SoundTrack(dir.resolve(name + ".pcm"), dir.resolve(name + ".info"), rate, ch);
        int n = rate / 50;
        byte[] buf = new byte[n * ch * 2];
        long s = 0;
        for (long end = startUs + 20_000; end <= startUs + lengthUs; end += 20_000) {
            for (int i = 0; i < n; i++, s++) {
                long at = startUs + s * 1_000_000L / rate;
                short v = at >= beepUs && at < beepUs + 200_000 ? (short) (Math.sin(2 * Math.PI * hz * s / rate) * 16_000) : 0;
                for (int c = 0; c < ch; c++) {
                    buf[(i * ch + c) * 2] = (byte) v;
                    buf[(i * ch + c) * 2 + 1] = (byte) (v >> 8);
                }
            }
            t.write(buf, 0, buf.length, startUs + s * 1_000_000L / rate);
        }
        t.finish(startUs + lengthUs);
    }

    /**
     * The whole exchange in real time: the stand-in launcher answers the
     * mod's start and stop, the generated microphone runs beside it, and
     * both tracks reach the video.
     */
    @Test
    void sessionWithStandIn(@TempDir Path dir) throws Exception {
        String ff = Ffmpeg.find();
        if (ff == null) return;
        long pid = ProcessHandle.current().pid();
        SoundLinkStandIn standIn = new SoundLinkStandIn(dir, pid);
        SoundSession.testTones = true;
        try {
            SoundSession s = SoundSession.start(dir, "rec1", true, true, "", pid);
            assertNotNull(s);
            assertTrue(s.notes().isEmpty(), String.valueOf(s.notes()));
            Thread.sleep(400);
            long v0 = s.clock().nowUs();
            ToneSource.beepUs = v0 + 600_000;
            Thread.sleep(1_600);
            s.stop();
            Path video = dir.resolve("rec1.mp4");
            Process p = new ProcessBuilder(ff, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "color=c=gray:s=64x64:r=30",
                    "-t", "1.5", "-c:v", "libx264", "-pix_fmt", "yuv420p", video.toString()).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            assertTrue(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0);
            assertNull(s.mux(ff, video, v0, 1.5), s.summary());
            assertEquals(1, standIn.starts);
            assertEquals(1, standIn.stops);
            assertEquals(List.of("Game", "Microphone"), SoundProbe.tracks(ff, video), s.summary());
            double[] g = SoundProbe.onsetAndLengthMs(ff, video, 0), m = SoundProbe.onsetAndLengthMs(ff, video, 1);
            assertEquals(600, g[0], 30, "game " + g[0] + " " + s.summary());
            assertEquals(600, m[0], 30, "microphone " + m[0] + " " + s.summary());
            assertFalse(Files.exists(SoundLink.pcm(dir, "rec1", "game")));
        } finally {
            SoundSession.testTones = false;
            ToneSource.beepUs = -1;
            standIn.close();
        }
    }
}
