package dev.breeze.devtest;

import dev.breeze.net.SpotifyPlayer;

/**
 * Self-test of the music on this computer (2.26.0; the Spotify app alone in
 * 2.25.0), on the Linux build servers only: run-minecraft-test.sh starts a
 * stand-in player on the session bus (fake-mpris.py: a browser playing a
 * song, as YouTube Music in Chromium does) and sets
 * breeze.autotest.localSpotify. The stand-in API is told the account has no
 * Spotify connected, so the player must come from the computer: the song,
 * the player's name and the position are read, then it is paused with the
 * same command the Music screen's Pause button sends, and the pause read
 * back. Logs local-spotify-check with pass true or the step it stopped at.
 */
final class LocalMediaCheck {

    private LocalMediaCheck() {}

    private static final boolean ON = "1".equals(System.getProperty("breeze.autotest.localSpotify"));

    private static int step;
    private static long stepAt;
    private static String title;
    private static long lastAsk;

    /** Whether the check has finished (or never runs here): the lyrics check waits for it. */
    static boolean finished() {
        return !ON || step < 0;
    }

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
                long position = s.positionKnown() ? s.progressNow(now) : -1;
                AutoTest.report("local-spotify", "title", s.track.title, "artist", s.track.artist,
                        "app", String.valueOf(s.deviceName), "positionMs", String.valueOf(position),
                        "why", String.valueOf(s.blocked), "durationMs", String.valueOf(s.track.durationMs),
                        "artwork", String.valueOf(s.track.artworkSmall));
                // The stand-in is a browser at 42 seconds with a cover file.
                boolean right = "Chromium".equals(s.deviceName) && position >= 41_000 && position <= 50_000
                        && s.track.artworkSmall != null && s.track.artworkSmall.startsWith("local:file:");
                if (!right) {
                    done(false, "read the song but not its player, position or cover (player " + s.deviceName
                            + ", position " + position + ", cover " + s.track.artworkSmall + ")");
                    return;
                }
                SpotifyPlayer.togglePlay();
                step = 2;
                stepAt = now;
            } else if (s.phase == SpotifyPlayer.Phase.READY && now - lastAsk > 5_000) {
                // A status read already on its way when the stand-in API was
                // switched answered "connected" (1.18.1 and 1.19.2 in run
                // 42517303439); switch it again and read the status afresh.
                lastAsk = now;
                AutoTest.stubPostFromTest("/test/spotify-local");
                SpotifyPlayer.recheck();
            } else if (age > 40_000) {
                done(false, "the music on this computer was never read (phase " + s.phase + ", why " + s.blocked
                        + ", track " + (s.track != null) + ", playing " + s.playing + ")");
            }
            return;
        }
        if (step == 2) {
            // The optimistic flip shows at once; wait for a reading after it.
            if (age > 2_500 && !s.playing && s.phase == SpotifyPlayer.Phase.LOCAL) {
                done(true, null);
            } else if (age > 15_000) {
                done(false, "Pause was sent but the player still plays");
            }
        }
    }

    private static void done(boolean pass, String why) {
        step = -1;
        AutoTest.report("local-spotify-check", "pass", String.valueOf(pass), "title", String.valueOf(title),
                "reason", why == null ? "" : why);
    }
}
