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
    private static int phase;
    private static long phaseAt;
    private static boolean shot3, shot6, swung;

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
                next(1);
            }
            case 1 -> {
                if (mc.player == null) return finish(mc, "the player left");
                mc.player.setYRot(mc.player.getYRot() + 2f);
                long ms = RecorderHooks.recordedMs();
                if (!shot3 && ms >= 3_000) {
                    shot3 = true;
                    // The first 3 s hold the world copy; from here on is what
                    // recording costs while playing ("recorder-on").
                    AutoTest.frameCost("recorder-start");
                    dev.breeze.perf.FrameCost.enable(true);
                    AutoTest.shot(mc, "rec-3s");
                    AutoTest.report("rec-moment", "name", "rec-3s", "ms", String.valueOf(ms), "yaw", String.valueOf(mc.player.getYRot()));
                }
                if (!shot6 && ms >= 6_000) {
                    shot6 = true;
                    AutoTest.shot(mc, "rec-6s");
                    AutoTest.report("rec-moment", "name", "rec-6s", "ms", String.valueOf(ms), "yaw", String.valueOf(mc.player.getYRot()));
                }
                if (ms >= LENGTH_MS) {
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
                return true;
            }
            default -> {
                Path f = newest(mc);
                if (f == null) return in > 10_000 ? finish(mc, "no recording in the recordings folder") : false;
                try (RecReader r = new RecReader(f)) {
                    if (!r.complete()) return in > 15_000 ? finish(mc, "the recording was not finished") : false;
                    int[] kinds = new int[8];
                    long records = 0;
                    for (RecReader.ChunkRef c : r.chunks()) {
                        for (RecReader.Rec rec : r.readChunk(c)) {
                            records++;
                            if (rec.kind() < kinds.length) kinds[rec.kind()]++;
                        }
                    }
                    int packets = kinds[RecFormat.Kind.PACKET], selfs = kinds[RecFormat.Kind.SELF];
                    boolean pass = r.meta().finished && packets > 50 && selfs >= 140 && selfs <= 180
                            && r.durationMs() >= LENGTH_MS && r.durationMs() < LENGTH_MS + 2_000
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
