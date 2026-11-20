package dev.breeze.devtest;

import dev.breeze.compat.ActiveScreen;
import dev.breeze.recorder.RecorderHooks;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;

/**
 * The second launch of the recorder test (-Dbreeze.autotest.watch=<file>):
 * from the title screen, the recording made by RecorderCheck is opened and
 * watched to the end, with screenshots at the same moments it took while
 * recording and the player's turn logged; then the game closes itself.
 * Nothing else of the self-test runs in this launch.
 */
final class WatchCheck {

    static final String FILE = System.getProperty("breeze.autotest.watch");
    /** Export instead of watch (-Dbreeze.autotest.export=fps): a third launch makes a video. */
    static final String EXPORT_FPS = System.getProperty("breeze.autotest.export");
    /** A resource pack to turn on first, the way Breeze's Packs page does (-Dbreeze.autotest.watchPack=id). */
    static final String PACK = System.getProperty("breeze.autotest.watchPack");
    private static boolean packAsked;
    private static final long STARTED = System.currentTimeMillis();
    private static int phase;
    private static long phaseAt;
    private static boolean shot3, shot6, barOpened, barShot;
    private static String last = "";

    private WatchCheck() {}

    static boolean on() {
        return FILE != null && !FILE.isBlank();
    }

    static void tick(Minecraft mc) {
        long in = System.currentTimeMillis() - phaseAt;
        switch (phase) {
            case 0 -> {
                if (System.currentTimeMillis() - STARTED < 20_000 || ActiveScreen.overlay(mc) != null || mc.level != null) return;
                if (PACK != null && !packAsked) {
                    packAsked = true;
                    try {
                        dev.breeze.packs.PackMenu.apply(mc, new java.util.ArrayList<>(java.util.List.of(PACK)));
                        AutoTest.report("watch-pack", "pack", PACK, "options", String.join(",", mc.options.resourcePacks));
                    } catch (Throwable t) {
                        AutoTest.report("watch-pack", "pack", PACK, "error", t.toString());
                    }
                    return;
                }
                if (EXPORT_FPS != null) {
                    String why = RecorderHooks.export(mc, Path.of(FILE), Integer.parseInt(EXPORT_FPS),
                            dev.breeze.recorder.VideoEncoder.Quality.STANDARD);
                    AutoTest.report("export-open", "file", FILE, "problem", why == null ? "" : why);
                    if (why != null) {
                        end(mc, why);
                        return;
                    }
                    phase = 3;
                    phaseAt = System.currentTimeMillis();
                    return;
                }
                String why = RecorderHooks.watch(mc, Path.of(FILE));
                AutoTest.report("watch-open", "file", FILE, "problem", why == null ? "" : why);
                if (why != null) {
                    end(mc, why);
                    return;
                }
                phase = 1;
                phaseAt = System.currentTimeMillis();
            }
            case 1 -> {
                String info = RecorderHooks.replayInfo();
                if (info == null) {
                    end(mc, "the recording stopped playing (" + last + ")");
                    return;
                }
                last = info;
                long pos = num(info, "pos"), length = num(info, "length");
                boolean world = info.contains("world=true");
                float yaw = mc.player == null ? Float.NaN : mc.player.getYRot();
                if (world && !shot3 && pos >= 3_000) {
                    shot3 = true;
                    AutoTest.shot(mc, "watch-3s");
                    AutoTest.report("watch-moment", "name", "watch-3s", "ms", String.valueOf(pos), "yaw", String.valueOf(yaw));
                }
                // The playback bar (the Recorder key while watching) between the two moments.
                if (world && !barOpened && pos >= 4_000) {
                    barOpened = true;
                    ActiveScreen.set(mc, new dev.breeze.menu.PlaybackScreen());
                }
                if (world && barOpened && !barShot && pos >= 4_800) {
                    barShot = true;
                    AutoTest.shot(mc, "watch-bar");
                    AutoTest.report("watch-bar", "screen", String.valueOf(ActiveScreen.get(mc) instanceof dev.breeze.menu.PlaybackScreen));
                    ActiveScreen.set(mc, null);
                }
                if (world && !shot6 && pos >= 6_000) {
                    shot6 = true;
                    AutoTest.shot(mc, "watch-6s");
                    AutoTest.report("watch-moment", "name", "watch-6s", "ms", String.valueOf(pos), "yaw", String.valueOf(yaw));
                }
                if (pos >= length && info.contains("playing=false")) {
                    boolean pass = world && shot3 && shot6 && num(info, "errors") == 0 && num(info, "fed") > 50;
                    AutoTest.report("watch-check", "info", info, "pass", String.valueOf(pass));
                    phase = 2;
                    phaseAt = System.currentTimeMillis();
                    return;
                }
                if (in > 120_000) end(mc, "the recording did not reach its end in two minutes (" + info + ")");
            }
            case 2 -> {
                if (in > 3_000) {
                    AutoTest.report("watch-done");
                    mc.stop();
                }
            }
            case 3 -> {
                String res = RecorderHooks.lastExport();
                if (res == null) {
                    if (in > 600_000) end(mc, "the export did not finish in ten minutes");
                    return;
                }
                java.nio.file.Path v = RecorderHooks.lastVideo();
                AutoTest.report("export-check", "result", res, "video", v == null ? "" : v.toAbsolutePath().toString(),
                        "seconds", String.valueOf(in / 1000), "pass", String.valueOf(v != null));
                phase = 2;
                phaseAt = System.currentTimeMillis();
            }
            default -> { }
        }
    }

    private static long num(String info, String key) {
        for (String part : info.split(" ")) {
            if (part.startsWith(key + "=")) {
                try {
                    return (long) Double.parseDouble(part.substring(key.length() + 1));
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private static void end(Minecraft mc, String problem) {
        AutoTest.report("watch-check", "problem", problem, "pass", "false");
        phase = 2;
        phaseAt = System.currentTimeMillis();
    }
}
