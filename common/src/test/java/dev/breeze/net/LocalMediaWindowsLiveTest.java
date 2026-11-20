package dev.breeze.net;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Windows reader on a real Windows: only on the build server's Windows
 * job (BREEZE_LIVE_MEDIA=1), which first starts a stand-in player that tells
 * Windows it plays a song (scripts/ci/fake-smtc.ps1).
 *
 * Always required: the PowerShell helper starts from its one-argument script
 * and answers a reading as JSON. That is the part no other test can reach.
 * Required when the stand-in registered with Windows (BREEZE_LIVE_SESSION=1):
 * the song, its player and its position come back through
 * {@link LocalMedia#read}. What was found is written to
 * build/live-media.txt for the run's evidence either way.
 */
@EnabledIfEnvironmentVariable(named = "BREEZE_LIVE_MEDIA", matches = "1")
class LocalMediaWindowsLiveTest {

    @Test
    void theHelperStartsAndReadsWhatWindowsPlays() throws Exception {
        List<String> notes = new ArrayList<>();
        Path out = Paths.get("build", "live-media.txt");
        try {
            String raw = LocalMediaWindows.helperAnswer();
            notes.add("helper answer: " + raw);
            assertNotNull(raw, "the PowerShell helper did not start or did not answer");
            assertTrue(raw.startsWith("{"), "not a reading: " + raw);

            LocalMedia.Reading r = LocalMedia.Reading.ABSENT;
            long until = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < until) {
                r = LocalMedia.read();
                if (r.hasTrack()) break;
                Thread.sleep(500);
            }
            notes.add("reading: present=" + r.present + " playing=" + r.playing + " title=" + r.title + " artist=" + r.artist
                    + " album=" + r.album + " app=" + r.app + " positionMs=" + r.positionMs + " durationMs=" + r.durationMs
                    + " cover=" + r.artworkUrl);
            if (r.hasTrack()) {
                byte[] cover = r.artworkUrl == null ? null : LocalMedia.artworkBytes(r.artworkUrl);
                notes.add("cover bytes: " + (cover == null ? "none" : String.valueOf(cover.length)));
                notes.add("play/pause accepted: " + LocalMedia.command(LocalMedia.Command.PLAY_PAUSE));
            }
            if ("1".equals(System.getenv("BREEZE_LIVE_SESSION"))) {
                assertTrue(r.hasTrack(), "the stand-in player registered with Windows but was not read");
                assertEquals("Windows Test Song", r.title);
                assertEquals("The Runner Testers", r.artist);
                assertEquals(Boolean.TRUE, r.playing);
                assertTrue(r.positionMs >= 41_000 && r.positionMs <= 120_000, "position " + r.positionMs);
                assertEquals(201_000, r.durationMs);
            }
            notes.add("result: passed");
        } catch (Throwable t) {
            notes.add("result: FAILED " + t);
            throw t;
        } finally {
            Files.createDirectories(out.getParent());
            Files.write(out, notes, StandardCharsets.UTF_8);
        }
    }
}
