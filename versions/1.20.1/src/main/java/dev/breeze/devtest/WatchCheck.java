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
    /**
     * Export instead of watch (-Dbreeze.autotest.export=30,240,30@2160): a
     * third launch makes one video after another, each at a frame rate (0 for
     * "As recorded") and, after @, a video height (none: the window's size).
     */
    static final String EXPORT_FPS = System.getProperty("breeze.autotest.export");
    private static final java.util.List<String> exports = EXPORT_FPS == null ? new java.util.ArrayList<>()
            : new java.util.ArrayList<>(java.util.Arrays.asList(EXPORT_FPS.split(",")));
    private static String exporting = "";
    /** A resource pack to turn on first, the way Breeze's Packs page does (-Dbreeze.autotest.watchPack=id). */
    static final String PACK = System.getProperty("breeze.autotest.watchPack");
    private static boolean packAsked;
    private static final long STARTED = System.currentTimeMillis();
    private static int phase;
    private static long phaseAt;
    private static boolean shot3, shot6, barOpened, barShot, freeOn, freeOff;
    private static double freeY;
    /** Ticks since the free camera was switched off (the recording moves the camera back on the next one). */
    private static int sinceFreeOff = -1;
    private static String last = "";
    /**
     * Recordings watched to their end before the main one
     * (-Dbreeze.autotest.watchFirst=a,b): one stopped in the middle of its
     * world copy, and one 2.30.0 saved that way (no player position, no
     * load-start packet). Each must play to its end, or be refused plainly,
     * and never hang on "Loading terrain".
     */
    static final String FIRST = System.getProperty("breeze.autotest.watchFirst");
    private static final java.util.List<String> firsts = FIRST == null || FIRST.isBlank() ? new java.util.ArrayList<>()
            : new java.util.ArrayList<>(java.util.Arrays.asList(FIRST.split(",")));
    /**
     * A recording from the earlier jar (2.30.0) to cut the way 2.30.0 saved
     * one stopped while it still copied the world
     * (-Dbreeze.autotest.watchCut=<file>): the connection's prelude and the
     * first part of the world copy, then nothing (no player position, no
     * load-start packet, nothing recorded after the start), the length kept.
     * 2.30.0 wrote exactly these records in this order; on the build
     * machines its copy finished within its 8 s recording, so the cut stands
     * for the owner's file. Watched first.
     */
    static final String CUT = System.getProperty("breeze.autotest.watchCut");
    private static boolean cutMade;
    private static String extra = "";
    private static String extraLast = "";
    private static boolean extraWorld;

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
                    nextExport(mc);
                    return;
                }
                if (CUT != null && !CUT.isBlank() && !cutMade) {
                    cutMade = true;
                    Path cut = cutLike230(Path.of(CUT));
                    if (cut != null) firsts.add(0, cut.toString());
                }
                if (!firsts.isEmpty()) {
                    extra = firsts.remove(0).trim();
                    extraLast = "";
                    extraWorld = false;
                    String why = RecorderHooks.watch(mc, Path.of(extra));
                    AutoTest.report("watch-extra-open", "file", extra, "problem", why == null ? "" : why);
                    if (why != null) {
                        AutoTest.report("watch-extra", "file", extra, "problem", why, "plain", "true", "pass", "false");
                        return;
                    }
                    phase = 10;
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
                // Free camera: the viewer's own position holds; back in the player's view the recording moves it again.
                if (world && !freeOn && pos >= 5_000 && mc.player != null && EXPORT_FPS == null) {
                    freeOn = true;
                    RecorderHooks.control(mc, "camera");
                    freeY = mc.player.getY() + 20;
                    mc.player.setPos(mc.player.getX(), freeY, mc.player.getZ());
                }
                if (world && freeOn && !freeOff && pos >= 5_500 && mc.player != null) {
                    freeOff = true;
                    double held = mc.player.getY();
                    boolean free = String.valueOf(RecorderHooks.replayInfo()).contains("camera=free");
                    RecorderHooks.control(mc, "camera");
                    sinceFreeOff = 0;
                    AutoTest.report("watch-freecam", "free", String.valueOf(free), "heldY", String.valueOf(held), "setY", String.valueOf(freeY),
                            "pass", String.valueOf(free && Math.abs(held - freeY) < 3));
                }
                if (sinceFreeOff >= 0) sinceFreeOff++;
                boolean cameraBack = !freeOn || (freeOff && sinceFreeOff >= 2);
                if (world && !shot6 && pos >= 6_000 && cameraBack) {
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
                    if (in > 900_000) end(mc, "the export did not finish in fifteen minutes");
                    return;
                }
                java.nio.file.Path v = RecorderHooks.lastVideo();
                String[] spec = exporting.split("@");
                AutoTest.report("export-check", "spec", exporting, "fps", spec[0], "size", spec.length > 1 ? spec[1] : "0",
                        "result", res, "video", v == null ? "" : v.toAbsolutePath().toString(),
                        "seconds", String.valueOf(in / 1000), "pass", String.valueOf(v != null));
                if (exports.isEmpty()) {
                    phase = 2;
                } else {
                    // The next one from the title screen, once the last has left its world.
                    phase = 4;
                }
                phaseAt = System.currentTimeMillis();
            }
            case 4 -> {
                if (in < 3_000 || mc.level != null || ActiveScreen.overlay(mc) != null) return;
                nextExport(mc);
            }
            case 10 -> {
                String info = RecorderHooks.replayInfo();
                if (info == null) {
                    // Given up (a plain message) or stopped.
                    String why = RecorderHooks.lastWatchProblem();
                    AutoTest.report("watch-extra", "file", extra, "info", extraLast, "problem", why == null ? "the recording stopped playing" : why,
                            "seconds", String.valueOf(in / 1000), "pass", "false");
                    phase = 11;
                    phaseAt = System.currentTimeMillis();
                    return;
                }
                extraLast = info;
                if (info.contains("world=true") && !extraWorld) {
                    extraWorld = true;
                    AutoTest.shot(mc, "watch-extra-" + (firsts.size() + 1));
                }
                long pos = num(info, "pos"), length = num(info, "length");
                if (pos >= length && info.contains("playing=false")) {
                    AutoTest.report("watch-extra", "file", extra, "info", info, "seconds", String.valueOf(in / 1000),
                            "y", String.valueOf(mc.player == null ? Double.NaN : mc.player.getY()),
                            "screen", String.valueOf(ActiveScreen.get(mc) == null ? "" : ActiveScreen.get(mc).getClass().getSimpleName()),
                            "pass", String.valueOf(info.contains("world=true")));
                    RecorderHooks.control(mc, "leave");
                    phase = 11;
                    phaseAt = System.currentTimeMillis();
                    return;
                }
                if (in > 90_000) {
                    AutoTest.report("watch-extra", "file", extra, "info", info, "problem", "not at its end in 90 s (hung)", "pass", "false");
                    RecorderHooks.control(mc, "leave");
                    phase = 11;
                    phaseAt = System.currentTimeMillis();
                }
            }
            case 11 -> {
                // Back on the title screen, then the next recording.
                if (in < 3_000 || mc.level != null || ActiveScreen.overlay(mc) != null) return;
                phase = 0;
            }
            default -> { }
        }
    }

    /** The earlier jar's recording cut as 2.30.0 saved one stopped mid-copy (see CUT), or null. */
    private static Path cutLike230(Path src) {
        Path out = src.resolveSibling("cut-" + src.getFileName());
        try (dev.breeze.recorder.RecReader r = new dev.breeze.recorder.RecReader(src)) {
            java.util.List<dev.breeze.recorder.RecReader.Rec> start = new java.util.ArrayList<>();
            for (dev.breeze.recorder.RecReader.ChunkRef c : r.chunks()) {
                for (dev.breeze.recorder.RecReader.Rec rec : r.readChunk(c)) {
                    if (rec.timeMs() == 0) start.add(rec);
                }
            }
            // The world copy is most of the records at the start, its rest
            // (player, entities, load-start) the last few hundred: keeping 40%
            // stops inside the chunks.
            int keep = (int) (start.size() * 0.4);
            dev.breeze.recorder.RecMeta meta = r.meta();
            meta.finished = false;
            meta.frames = 0;
            meta.fps = 0;
            dev.breeze.recorder.RecWriter w = new dev.breeze.recorder.RecWriter(out, meta);
            for (int i = 0; i < keep; i++) w.record(start.get(i).kind(), 0, start.get(i).data());
            w.close(r.durationMs());
            AutoTest.report("watch-cut", "from", src.toString(), "file", out.toString(), "kept", String.valueOf(keep),
                    "ofStart", String.valueOf(start.size()), "durationMs", String.valueOf(r.durationMs()));
            return out;
        } catch (Exception e) {
            AutoTest.report("watch-cut", "from", src.toString(), "problem", e.toString());
            return null;
        }
    }

    /** Starts the next export of the list (from the title screen). */
    private static void nextExport(Minecraft mc) {
        exporting = exports.remove(0).trim();
        String[] spec = exporting.split("@");
        int fps = Integer.parseInt(spec[0]);
        int size = spec.length > 1 ? Integer.parseInt(spec[1]) : 0;
        String why = RecorderHooks.export(mc, Path.of(FILE), fps, size, dev.breeze.recorder.VideoEncoder.Quality.STANDARD);
        AutoTest.report("export-open", "file", FILE, "spec", exporting, "problem", why == null ? "" : why);
        if (why != null) {
            end(mc, why);
            return;
        }
        phase = 3;
        phaseAt = System.currentTimeMillis();
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
