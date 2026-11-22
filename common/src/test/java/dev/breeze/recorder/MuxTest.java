package dev.breeze.recorder;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MuxTest {

    @Test
    void fitDelaysTrimsAndPads() {
        assertEquals("[1:a]adelay=250:all=1,apad,atrim=duration=10.0000[a0]", Mux.fit(1, 0.25, 10, "a0"));
        assertEquals("[2:a]atrim=start=0.1000,asetpts=PTS-STARTPTS,apad,atrim=duration=5.0000[a1]", Mux.fit(2, -0.1, 5, "a1"));
        assertEquals("[1:a]apad,atrim=duration=3.0000[g]", Mux.fit(1, 0, 3, "g"));
    }

    @Test
    void editMixesKeptTracksOnly() {
        Path v = Path.of("v.mp4");
        String both = String.join(" ", Mux.edit("ffmpeg", v, v, new double[]{1, 0.5}, 0, Path.of("o.mp4")));
        assertTrue(both.contains("[0:a:0]volume=1.000[v0];[0:a:1]volume=0.500[v1];[v0][v1]amix=inputs=2:duration=first:normalize=0[mix]"), both);
        String gameOnly = String.join(" ", Mux.edit("ffmpeg", v, v, new double[]{1, 0}, 0, Path.of("o.mp4")));
        assertTrue(gameOnly.contains("[0:a:0]volume=1.000[v0] -map [v0]"), gameOnly);
        String none = String.join(" ", Mux.edit("ffmpeg", v, v, new double[]{0, 0}, 0, Path.of("o.mp4")));
        assertTrue(none.contains(" -an "), none);
        String render = String.join(" ", Mux.edit("ffmpeg", Path.of("r.mp4"), v, new double[]{1}, 0.05, Path.of("o.mp4")));
        assertTrue(render.contains("-itsoffset 0.0500 -i"), render);
        assertTrue(render.contains("[1:a:0]volume=1.000"), render);
        assertNull(Mux.previewSound("ffmpeg", v, 0, new double[]{0, 0}));
    }

    @Test
    void audioLinkStatusAndResult(@TempDir Path game) throws Exception {
        assertFalse(AudioLink.status(game).launcher());
        Path dir = AudioLink.dir(game);
        Files.createDirectories(dir);
        long now = System.currentTimeMillis();
        Files.writeString(dir.resolve("status.json"), "{\"updated\":" + now + ",\"game\":true,\"mic\":true,\"reason\":\"\",\"devices\":[{\"id\":\"{0.0.1}\",\"name\":\"Mic (USB)\"}]}");
        AudioLink.Status s = AudioLink.status(game, now + 1000);
        assertTrue(s.launcher() && s.game() && s.mic());
        assertEquals("Mic (USB)", s.devices().get(0).name());
        assertFalse(AudioLink.status(game, now + 20_000).launcher());
        assertTrue(AudioLink.start(game, "2026-11-22_10.00.00", true, true, "{0.0.1}"));
        assertTrue(Files.readString(dir.resolve("start.json")).contains("\"micDevice\":\"{0.0.1}\""));
        assertFalse(AudioLink.start(game, "../evil", true, false, ""));
        Files.write(dir.resolve("x.game.wav"), new byte[100]);
        Files.writeString(dir.resolve("2026-11-22_10.00.00.json"), "{\"done\":true,\"game\":{\"file\":\"x.game.wav\",\"startEpochMs\":1234.5},\"mic\":{\"file\":\"../../secret\"}}");
        AudioLink.Result r = AudioLink.await(game, "2026-11-22_10.00.00", 1000);
        assertNotNull(r.game());
        assertEquals(1234.5, r.game().startEpochMs());
        assertNull(r.mic());
        assertNull(r.problem());
    }

    private static String run(List<String> cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, p.exitValue(), out + "\n" + cmd);
        return out.trim();
    }

    /** A real join and edit: two offset tracks become two tracks of the video's length, then one mix. */
    @Test
    void joinAndEditWithFfmpeg(@TempDir Path d) throws Exception {
        Assumptions.assumeTrue(Ffmpeg.runs("ffmpeg") && Ffmpeg.runs("ffprobe"), "ffmpeg not installed here");
        Path v = d.resolve("v.mp4"), g = d.resolve("g.wav"), m = d.resolve("m.wav"), j = d.resolve("j.mp4"), e = d.resolve("e.mp4");
        run(List.of("ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=size=64x36:rate=30", "-t", "3", "-pix_fmt", "yuv420p", "-c:v", "libx264", v.toString()));
        run(List.of("ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "2.5", "-ac", "2", g.toString()));
        run(List.of("ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=880:sample_rate=48000", "-t", "4", "-ac", "1", m.toString()));
        run(Mux.join("ffmpeg", v, List.of(new Mux.In(g, 0.2, "Game"), new Mux.In(m, -0.3, "Microphone")), 3.0, j));
        String streams = run(List.of("ffprobe", "-v", "error", "-show_entries", "stream=codec_type,duration", "-of", "csv=p=0", j.toString()));
        assertEquals(3, streams.lines().count(), streams);
        String titles = run(List.of("ffprobe", "-v", "error", "-select_streams", "a", "-show_entries", "stream_tags=handler_name", "-of", "csv=p=0", j.toString()));
        assertTrue(titles.contains("Game") && titles.contains("Microphone"), titles);
        for (String line : streams.lines().toList()) {
            double dur = Double.parseDouble(line.split(",")[1]);
            assertEquals(3.0, dur, 0.1, streams);
        }
        run(Mux.edit("ffmpeg", j, j, new double[]{1.0, 0.5}, 0, e));
        String out = run(List.of("ffprobe", "-v", "error", "-show_entries", "stream=codec_type,channels", "-of", "csv=p=0", e.toString()));
        assertEquals(2, out.lines().count(), out);
        assertTrue(out.contains("audio,2"), out);
    }
}
