package dev.breeze.devtest;

import dev.breeze.Module;
import dev.breeze.ModuleManager;
import dev.breeze.recorder.RecFormat;
import dev.breeze.recorder.RecReader;
import dev.breeze.recorder.RecorderHooks;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * The recorder in a real game (1.2.3): what Breeze costs per tick with the
 * recorder off, then the Recorder module's key starts a recording while the
 * player turns slowly; screenshots at about 3 and 6
 * seconds with the turn logged; the key again stops it. The file must be
 * finished and hold the world, the packets and a state for every tick.
 * A second launch (WatchCheck) watches it back and takes the same moments.
 */
final class RecorderCheck {

    static final long LENGTH_MS = 8_000;
    /** The recording's length: 8 s, or longer when the world copy takes most of that (at least 5 s after it). */
    private static long length = LENGTH_MS;
    /** When the world copy was complete (recorded milliseconds), or -1 while it is made. */
    private static long copyDoneMs = -1;
    private static int phase;
    private static long phaseAt;
    private static boolean shot3, shot6, swung;
    private static boolean midStoppedWhileCopying;
    private static String midCopy = "";

    private RecorderCheck() {}

    private static Module module() {
        for (Module m : ModuleManager.getModules()) if (m.getName().equals("Recorder")) return m;
        return null;
    }

    static boolean start(Minecraft mc) {
        if (!RecorderHooks.supported() || module() == null) {
            AutoTest.report("rec-check", "pass", "true", "skipped", "no recorder on this version yet");
            return false;
        }
        phase = 0;
        phaseAt = System.currentTimeMillis();
        dev.breeze.perf.FrameCost.enable(true);
        return true;
    }

    private static void next(int p) {
        phase = p;
        phaseAt = System.currentTimeMillis();
    }

    /** True when the check is over. */
    static boolean step(Minecraft mc, long age) {
        long in = System.currentTimeMillis() - phaseAt;
        switch (phase) {
            case 0 -> {
                if (in < 4_000) return false;
                AutoTest.frameCost("recorder-off");
                dev.breeze.perf.FrameCost.enable(true);
                module().setEnabled(true);
                AutoTest.report("rec-started", "recording", String.valueOf(RecorderHooks.recording()));
                if (!RecorderHooks.recording()) return finish(mc, "the Recorder key did not start a recording");
                shot3 = shot6 = swung = false;
                length = LENGTH_MS;
                copyDoneMs = -1;
                next(1);
            }
            case 1 -> {
                if (mc.player == null) return finish(mc, "the player left");
                mc.player.setYRot(mc.player.getYRot() + 2f);
                long ms = RecorderHooks.recordedMs();
                if (copyDoneMs < 0 && !RecorderHooks.copying()) {
                    // The world copy is complete: what it cost each frame
                    // ("recorder-copy", against "recorder-off" before), and
                    // from here on what recording costs while playing ("recorder-on").
                    copyDoneMs = ms;
                    length = Math.max(LENGTH_MS, copyDoneMs + 5_000);
                    AutoTest.frameCost("recorder-copy");
                    dev.breeze.perf.FrameCost.enable(true);
                    copyReport(mc);
                }
                if (copyDoneMs < 0 && ms > 60_000) return finish(mc, "the world copy took more than a minute: " + RecorderHooks.stats());
                if (!shot3 && ms >= 3_000) {
                    shot3 = true;
                    AutoTest.shot(mc, "rec-3s");
                    AutoTest.report("rec-moment", "name", "rec-3s", "ms", String.valueOf(ms), "yaw", String.valueOf(mc.player.getYRot()));
                }
                if (!shot6 && ms >= 6_000) {
                    shot6 = true;
                    AutoTest.shot(mc, "rec-6s");
                    AutoTest.report("rec-moment", "name", "rec-6s", "ms", String.valueOf(ms), "yaw", String.valueOf(mc.player.getYRot()));
                }
                if (copyDoneMs >= 0 && ms >= length) {
                    ShotsCheck.recordingLine = null;
                    AutoTest.frameCost("recorder-on");
                    AutoTest.report("rec-stats", "stats", RecorderHooks.stats());
                    module().setEnabled(false);
                    next(2);
                }
            }
            case 3 -> {
                // The saved line in chat, then the Recordings page.
                net.minecraft.network.chat.Component line = ShotsCheck.recordingLine;
                if (line == null && in < 5_000) return false;
                String text = line == null ? "" : line.getString();
                boolean ok = text.contains("[Watch]") && text.contains("[Export]") && text.contains("[Recordings]");
                dev.breeze.menu.RecordingsScreen page = new dev.breeze.menu.RecordingsScreen(null);
                dev.breeze.compat.ActiveScreen.set(mc, page);
                AutoTest.report("rec-ui", "line", text, "listed", String.valueOf(page.count()), "pass", String.valueOf(ok && page.count() >= 1));
                next(4);
            }
            case 4 -> {
                if (in < 1_500) return false;
                AutoTest.shot(mc, "recordings-page");
                next(5);
            }
            case 5 -> {
                if (in < 1_000) return false;
                dev.breeze.compat.ActiveScreen.set(mc, null);
                next(6);
            }
            case 6 -> {
                // A recording stopped in the middle of its world copy must
                // still be complete and watchable (WatchCheck watches it).
                if (in < 2_000) return false;
                module().setEnabled(true);
                if (!RecorderHooks.recording()) return finish(mc, "the Recorder key did not start the second recording");
                next(7);
            }
            case 7 -> {
                if (mc.player != null) mc.player.setYRot(mc.player.getYRot() + 2f);
                if (RecorderHooks.recordedMs() < 300) return false;
                midStoppedWhileCopying = RecorderHooks.copying();
                long t0 = System.nanoTime();
                module().setEnabled(false);
                long stopMs = (System.nanoTime() - t0) / 1_000_000;
                midCopy = RecorderHooks.lastCopy();
                AutoTest.report("rec-midcopy-stop", "whileCopying", String.valueOf(midStoppedWhileCopying),
                        "stopMs", String.valueOf(stopMs), "copy", midCopy);
                next(8);
            }
            case 8 -> {
                if (in < 1_000) return false;
                Path f = newest(mc);
                if (f == null) return in > 10_000 ? midFail("no second recording") : false;
                try (RecReader r = new RecReader(f)) {
                    if (!r.complete()) return in > 15_000 ? midFail("the second recording was not finished") : false;
                    int[] kinds = new int[8];
                    for (RecReader.ChunkRef c : r.chunks()) for (RecReader.Rec rec : r.readChunk(c)) if (rec.kind() < kinds.length) kinds[rec.kind()]++;
                    boolean pass = midStoppedWhileCopying && r.meta().finished && kinds[RecFormat.Kind.PROTOCOL] == 3
                            && kinds[RecFormat.Kind.PACKET] > 20 && kinds[RecFormat.Kind.SELF] >= 1 && midCopy.contains("chunks=");
                    AutoTest.report("rec-midcopy", "file", f.toAbsolutePath().toString(), "durationMs", String.valueOf(r.durationMs()),
                            "packets", String.valueOf(kinds[RecFormat.Kind.PACKET]), "self", String.valueOf(kinds[RecFormat.Kind.SELF]),
                            "camera", String.valueOf(kinds[RecFormat.Kind.CAMERA]), "copy", midCopy,
                            "whileCopying", String.valueOf(midStoppedWhileCopying), "pass", String.valueOf(pass));
                    return true;
                } catch (Exception e) {
                    return in > 15_000 ? midFail("the second recording could not be read: " + e) : false;
                }
            }
            default -> {
                Path f = newest(mc);
                if (f == null) return in > 10_000 ? finish(mc, "no recording in the recordings folder") : false;
                try (RecReader r = new RecReader(f)) {
                    if (!r.complete()) return in > 15_000 ? finish(mc, "the recording was not finished") : false;
                    int[] kinds = new int[8];
                    long records = 0;
                    java.util.List<double[]> cams = new java.util.ArrayList<>(), ticks = new java.util.ArrayList<>();
                    for (RecReader.ChunkRef c : r.chunks()) {
                        for (RecReader.Rec rec : r.readChunk(c)) {
                            records++;
                            if (rec.kind() < kinds.length) kinds[rec.kind()]++;
                            byte[] d = rec.data();
                            if (rec.kind() == RecFormat.Kind.CAMERA && d.length >= dev.breeze.recorder.CameraFrames.SIZE) {
                                cams.add(new double[]{rec.timeMs() + dev.breeze.recorder.CameraFrames.micros(d) / 1000.0, dev.breeze.recorder.CameraFrames.yaw(d)});
                            } else if (rec.kind() == RecFormat.Kind.SELF && d.length >= 33) {
                                ticks.add(new double[]{rec.timeMs(), java.nio.ByteBuffer.wrap(d).getFloat(25)});
                            }
                        }
                    }
                    frames(r, cams, ticks);
                    int packets = kinds[RecFormat.Kind.PACKET], selfs = kinds[RecFormat.Kind.SELF];
                    boolean pass = r.meta().finished && packets > 50 && selfs >= length * 0.0175 && selfs <= length * 0.0225 + 2
                            && r.durationMs() >= length && r.durationMs() < length + 2_000
                            && kinds[RecFormat.Kind.PROTOCOL] == 3;
                    AutoTest.report("rec-check", "file", f.toAbsolutePath().toString(), "bytes", String.valueOf(Files.size(f)),
                            "durationMs", String.valueOf(r.durationMs()), "chunks", String.valueOf(r.chunks().size()),
                            "records", String.valueOf(records), "packets", String.valueOf(packets), "self", String.valueOf(selfs),
                            "minecraft", r.meta().minecraft, "world", r.meta().world,
                            "finished", String.valueOf(r.meta().finished), "protocols", String.valueOf(kinds[RecFormat.Kind.PROTOCOL]),
                            "thumbnail", String.valueOf(Files.isRegularFile(f.getParent().resolve(".thumbs").resolve(f.getFileName() + ".jpg"))),
                            "pass", String.valueOf(pass));
                    next(3);
                    return false;
                } catch (Exception e) {
                    return in > 15_000 ? finish(mc, "the recording could not be read: " + e) : false;
                }
            }
        }
        return false;
    }

    /**
     * The camera of every frame (1.2.3): frames in time order, turning the
     * way the player turned (the test turns 2 degrees a tick, so the frames'
     * turn never goes back, each frame shows the turn of the tick just
     * before it, to within that tick's turn, and the whole turn matches the
     * ticks'), the frame rate in the header, and the camera the playback
     * reads (CameraTrack) agrees with the frames. The build machines draw a
     * few frames a second, so nothing here asks for more frames than ticks.
     */
    private static void frames(RecReader r, java.util.List<double[]> cams, java.util.List<double[]> ticks) {
        int n = cams.size(), back = 0, unordered = 0, matched = 0, compared = 0, tracked = 0;
        double worst = 0;
        for (int i = 1; i < n; i++) {
            if (cams.get(i)[0] < cams.get(i - 1)[0]) unordered++;
            if (cams.get(i)[1] < cams.get(i - 1)[1] - 0.01) back++;
        }
        float[] out = new float[2];
        dev.breeze.recorder.CameraTrack track = new dev.breeze.recorder.CameraTrack(r);
        boolean fromFrames = false;
        int k = 0;
        for (double[] c : cams) {
            // Past the world copy, while the test turns every tick.
            if (c[0] < 1_000 || c[0] > length - 200) continue;
            while (k + 1 < ticks.size() && ticks.get(k + 1)[0] <= c[0]) k++;
            if (ticks.isEmpty() || ticks.get(k)[0] > c[0]) continue;
            double d = Math.abs(c[1] - ticks.get(k)[1]);
            compared++;
            if (d <= 2.5) matched++;
            worst = Math.max(worst, d);
            if (track.at(c[0], out) && Math.abs(out[0] - c[1]) < 0.01) tracked++;
            fromFrames |= track.fromFrames();
        }
        double seconds = r.durationMs() / 1000.0;
        double rate = n / Math.max(0.001, seconds);
        double yawFrames = n < 2 ? 0 : cams.get(n - 1)[1] - cams.get(0)[1];
        double yawTicks = ticks.size() < 2 ? 0 : ticks.get(ticks.size() - 1)[1] - ticks.get(0)[1];
        boolean pass = n >= seconds * 2 && r.meta().frames == n && r.meta().fps > 0 && Math.abs(r.meta().fps - rate) < 1.5
                && unordered == 0 && back == 0 && fromFrames && compared >= 10 && matched >= compared * 0.9
                && tracked >= compared * 0.98 && Math.abs(yawFrames - yawTicks) < 6;
        AutoTest.report("rec-frames", "frames", String.valueOf(n), "ticks", String.valueOf(ticks.size()),
                "fps", String.valueOf(r.meta().fps), "metaFrames", String.valueOf(r.meta().frames),
                "unordered", String.valueOf(unordered), "turnedBack", String.valueOf(back),
                "yawFrames", String.format(java.util.Locale.ROOT, "%.1f", yawFrames), "yawTicks", String.format(java.util.Locale.ROOT, "%.1f", yawTicks),
                "framesMatchingTheirTick", matched + "/" + compared, "worstDeg", String.format(java.util.Locale.ROOT, "%.2f", worst),
                "playbackAtFrames", tracked + "/" + compared, "pass", String.valueOf(pass));
    }

    /**
     * The world copy at the start of the recording: how long it took at the
     * render distance in use, and what it cost each frame (worst and
     * average; Breeze's own frame section with the recorder off before, and
     * while copying).
     */
    private static void copyReport(Minecraft mc) {
        String copy = RecorderHooks.lastCopy();
        long worst = num(copy, "worstFrameUs"), avg = num(copy, "avgFrameUs");
        long ms = num(copy, "ms");
        boolean pass = copy.contains("chunks=") && num(copy, "chunks") > 0 && num(copy, "skipped") == 0
                && worst <= 15_000 && ms <= 30_000;
        AutoTest.report("rec-copy", "renderDistance", String.valueOf(num(copy, "renderDistance")),
                "copy", copy, "copyDoneAtMs", String.valueOf(copyDoneMs),
                "worstFrameUs", String.valueOf(worst), "avgFrameUs", String.valueOf(avg), "pass", String.valueOf(pass));
    }

    private static long num(String s, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:^|[ ,])" + key + "=(-?\\d+)").matcher(s);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    private static boolean midFail(String problem) {
        AutoTest.report("rec-midcopy", "problem", problem, "pass", "false");
        return true;
    }

    private static Path newest(Minecraft mc) {
        Path dir = mc.gameDirectory.toPath().resolve("recordings");
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(RecFormat.EXTENSION))
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified())).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean finish(Minecraft mc, String problem) {
        Module m = module();
        if (m != null && m.isEnabled()) m.setEnabled(false);
        AutoTest.report("rec-check", "problem", problem, "pass", "false");
        return true;
    }
}
