package dev.breeze.devtest;

import dev.breeze.net.SpotifyPlayer;

/**
 * Self-test of the local Spotify control (2.25.0), on the Linux build
 * servers only: run-minecraft-test.sh starts a stand-in Spotify on the
 * session bus (fake-mpris.py) and sets breeze.autotest.localSpotify. The
 * stand-in API is told the account has no Spotify connected, so the player
 * must come from the computer: the song is read, then paused with the same
 * command the Music screen's Pause button sends, and the pause read back.
 * Logs local-spotify-check with pass true or the step it stopped at.
 */
final class LocalSpotifyCheck {

    private LocalSpotifyCheck() {}

    private static final boolean ON = "1".equals(System.getProperty("breeze.autotest.localSpotify"));

    private static int step;
    private static long stepAt;
    private static String title;

    static void tick() {
        if (!ON || step < 0 || !AutoTest.running()) return;
        long now = System.currentTimeMillis();
        if (step == 0) {
            AutoTest.stubPostFromTest("/test/spotify-local");
            SpotifyPlayer.recheck();
            step = 1;
            stepAt = now;
            return;
        }
        // Read often while the check runs, as the open Music screen does.
        SpotifyPlayer.tick(now, 1_000);
        SpotifyPlayer.State s = SpotifyPlayer.state();
        long age = now - stepAt;
        if (step == 1) {
            if (s.phase == SpotifyPlayer.Phase.LOCAL && s.track != null && s.playing) {
                title = s.track.title;
                AutoTest.report("local-spotify", "title", s.track.title, "artist", s.track.artist,
                        "why", String.valueOf(s.blocked), "durationMs", String.valueOf(s.track.durationMs),
                        "artwork", String.valueOf(s.track.artworkSmall != null));
                SpotifyPlayer.togglePlay();
                step = 2;
                stepAt = now;
            } else if (age > 40_000) {
                done(false, "the local Spotify was never read (phase " + s.phase + ", why " + s.blocked
                        + ", track " + (s.track != null) + ", playing " + s.playing + ")");
            }
            return;
        }
        if (step == 2) {
            // The optimistic flip shows at once; wait for a reading after it.
            if (age > 2_500 && !s.playing && s.phase == SpotifyPlayer.Phase.LOCAL) {
                done(true, null);
            } else if (age > 15_000) {
                done(false, "Pause was sent but the local Spotify still plays");
            }
        }
    }

    private static void done(boolean pass, String why) {
        step = -1;
        AutoTest.report("local-spotify-check", "pass", String.valueOf(pass), "title", String.valueOf(title),
                "reason", why == null ? "" : why);
    }
}
