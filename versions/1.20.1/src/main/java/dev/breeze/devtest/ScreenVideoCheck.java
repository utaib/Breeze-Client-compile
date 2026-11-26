package dev.breeze.devtest;

import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The screen video of the recorder test (RecorderCheck), read back with
 * ffmpeg after it is saved: it plays, holds as many frames as its length
 * at its frame rate, and the frames taken while the inventory and the pause
 * menu were open show them (the inventory's grey panel; the pause menu
 * differs from the world a second later). The frames compared are kept
 * beside the game's screenshots, to look at.
 */
final class ScreenVideoCheck {

    private ScreenVideoCheck() {}

    /** Recorded while the test ran: video milliseconds when each screen opened and closed (-1 when not seen). */
    static long invOpen = -1, invClose = -1, pauseOpen = -1, pauseClose = -1;

    /** The video's time when the generated sound beeped (the inventory opened), or -1. */
    static long beepVideoMs = -1;

    static void reset() {
        invOpen = invClose = pauseOpen = pauseClose = -1;
        beepVideoMs = -1;
    }

    /** Checks the saved video and reports "rec-video". Off the render thread (ffmpeg runs a few times). */
    static void check(Minecraft mc, Path video, int fps, long expectedTicks, String stats) {
        Thread t = new Thread(() -> {
            try {
                run(mc, video, fps, expectedTicks, stats);
            } catch (Throwable e) {
                AutoTest.report("rec-video", "error", e.toString(), "pass", "false");
            }
        }, "Breeze self-test video check");
        t.setDaemon(true);
        t.start();
    }

    private static void run(Minecraft mc, Path video, int fps, long expectedTicks, String stats) throws Exception {
        String ff = dev.breeze.recorder.Ffmpeg.find();
        if (ff == null || video == null || !Files.isRegularFile(video)) {
            AutoTest.report("rec-video", "problem", "no video or no ffmpeg", "file", String.valueOf(video), "pass", "false");
            return;
        }
        // Decoding every frame: plays to its end, and counts them.
        String log = runFf(List.of(ff, "-hide_banner", "-nostdin", "-i", video.toString(), "-map", "0:v:0", "-f", "null", "-"));
        long frames = -1;
        Matcher m = Pattern.compile("frame=\\s*(\\d+)").matcher(log);
        while (m.find()) frames = Long.parseLong(m.group(1));
        Matcher size = Pattern.compile("Video: h264[^\\n]*?, (\\d{2,5})x(\\d{2,5})").matcher(log);
        String wh = size.find() ? size.group(1) + "x" + size.group(2) : "?";
        Matcher rate = Pattern.compile("(\\d+(?:\\.\\d+)?) fps").matcher(log);
        String r = rate.find() ? rate.group(1) : "?";
        boolean decodes = !log.contains("Invalid data") && !log.toLowerCase(Locale.ROOT).contains("error while decoding");
        Path shots = mc.gameDirectory.toPath().resolve("screenshots");
        Files.createDirectories(shots);
        double invGrey = -1, worldGrey = -1, pauseDiff = -1, invCentre = -1, worldCentre = -1, invDiff = -1;
        if (invOpen >= 0 && invClose > invOpen) {
            double mid = (invOpen + invClose) / 2000.0;
            int[] inv = frame(ff, video, mid, shots.resolve("rec-video-inventory.png"));
            int[] before = frame(ff, video, Math.max(0, invOpen / 1000.0 - 1.0), shots.resolve("rec-video-before-inventory.png"));
            invGrey = grey(inv);
            worldGrey = grey(before);
            invCentre = centreGrey(inv);
            worldCentre = centreGrey(before);
            invDiff = diff(inv, before);
        }
        if (pauseOpen >= 0 && pauseClose > pauseOpen) {
            int[] pause = frame(ff, video, (pauseOpen + pauseClose) / 2000.0, shots.resolve("rec-video-pause.png"));
            int[] after = frame(ff, video, pauseClose / 1000.0 + 1.0, shots.resolve("rec-video-after-pause.png"));
            pauseDiff = diff(pause, after);
        }
        boolean lengthOk = frames > 0 && Math.abs(frames - expectedTicks) <= 2;
        // The inventory's panel covers the middle of the screen: much of it its grey, and the picture changed.
        boolean inventoryShown = invCentre >= 0.25 && invCentre > worldCentre + 0.15;
        boolean pauseShown = pauseDiff >= 12;
        boolean pass = decodes && lengthOk && inventoryShown && pauseShown && ("" + fps).equals(r.replaceAll("\\.0+$", ""));
        AutoTest.report("rec-video", "file", video.toAbsolutePath().toString(), "bytes", String.valueOf(Files.size(video)),
                "frames", String.valueOf(frames), "expectedFrames", String.valueOf(expectedTicks), "size", wh, "fps", r,
                "seconds", String.format(Locale.ROOT, "%.2f", frames / (double) Math.max(1, fps)),
                "inventoryGrey", String.format(Locale.ROOT, "%.3f", invGrey), "worldGrey", String.format(Locale.ROOT, "%.3f", worldGrey),
                "pauseDiff", String.format(Locale.ROOT, "%.1f", pauseDiff),
                "inventorySlotGrey", String.format(Locale.ROOT, "%.3f", invCentre), "worldSlotGrey", String.format(Locale.ROOT, "%.3f", worldCentre),
                "inventoryDiff", String.format(Locale.ROOT, "%.1f", invDiff),
                "inventoryAtMs", invOpen + "-" + invClose, "pauseAtMs", pauseOpen + "-" + pauseClose,
                "stats", stats, "decodes", String.valueOf(decodes), "pass", String.valueOf(pass));
        sound(ff, video, frames / (double) Math.max(1, fps));
    }

    /**
     * Round 2: the video holds two audio tracks named Game and Microphone,
     * each as long as the video, and each one's beep (made when the
     * inventory opened) is where the inventory opens in the picture.
     */
    private static void sound(String ff, Path video, double seconds) {
        List<String> tracks = dev.breeze.recorder.SoundProbe.tracks(ff, video);
        double[] game = tracks.size() > 0 ? dev.breeze.recorder.SoundProbe.onsetAndLengthMs(ff, video, 0) : new double[]{-1, -1};
        double[] mic = tracks.size() > 1 ? dev.breeze.recorder.SoundProbe.onsetAndLengthMs(ff, video, 1) : new double[]{-1, -1};
        double ms = seconds * 1000;
        boolean named = tracks.equals(List.of("Game", "Microphone"));
        boolean lengths = Math.abs(game[1] - ms) <= 80 && Math.abs(mic[1] - ms) <= 80;
        // The beep was asked for on a tick; a 20 ms piece and the frame's own time make up the slack.
        boolean inTime = beepVideoMs >= 0 && Math.abs(game[0] - beepVideoMs) <= 80 && Math.abs(mic[0] - beepVideoMs) <= 80;
        AutoTest.report("rec-sound", "tracks", String.valueOf(tracks), "videoMs", String.format(Locale.ROOT, "%.0f", ms),
                "beepAtVideoMs", String.valueOf(beepVideoMs),
                "gameBeepMs", String.format(Locale.ROOT, "%.1f", game[0]), "gameLengthMs", String.format(Locale.ROOT, "%.0f", game[1]),
                "micBeepMs", String.format(Locale.ROOT, "%.1f", mic[0]), "micLengthMs", String.format(Locale.ROOT, "%.0f", mic[1]),
                "sound", dev.breeze.recorder.ScreenCapture.lastSound(),
                "standInStarts", String.valueOf(RecorderCheck.standIn == null ? -1 : RecorderCheck.standIn.starts),
                "standInStops", String.valueOf(RecorderCheck.standIn == null ? -1 : RecorderCheck.standIn.stops),
                "pass", String.valueOf(named && lengths && inTime));
        dev.breeze.recorder.SoundSession.testTones = false;
        dev.breeze.recorder.ToneSource.beepUs = -1;
    }

    /** One frame at a moment, as 160 by 90 RGB, also kept as a full-size PNG. */
    private static int[] frame(String ff, Path video, double sec, Path png) throws Exception {
        String at = String.format(Locale.ROOT, "%.3f", sec);
        runFf(List.of(ff, "-hide_banner", "-nostdin", "-y", "-ss", at, "-i", video.toString(), "-frames:v", "1", png.toString()));
        Process p = new ProcessBuilder(ff, "-hide_banner", "-nostdin", "-loglevel", "error", "-ss", at, "-i", video.toString(),
                "-frames:v", "1", "-vf", "scale=160:90", "-f", "rawvideo", "-pix_fmt", "rgb24", "-").start();
        p.getErrorStream().close();
        byte[] b = p.getInputStream().readAllBytes();
        p.waitFor(60, TimeUnit.SECONDS);
        int[] px = new int[b.length];
        for (int i = 0; i < b.length; i++) px[i] = b[i] & 0xFF;
        return px;
    }

    /** The share of pixels of the inventory's panel grey (198, 198, 198, give or take). */
    private static double grey(int[] rgb) {
        int n = rgb.length / 3, hit = 0;
        for (int i = 0; i < n; i++) {
            int r = rgb[i * 3], g = rgb[i * 3 + 1], b = rgb[i * 3 + 2];
            if (Math.abs(r - 198) <= 14 && Math.abs(g - 198) <= 14 && Math.abs(b - 198) <= 14) hit++;
        }
        return n == 0 ? -1 : hit / (double) n;
    }

    /**
     * The share of the middle of the picture (35 to 65% across, 30 to 70%
     * down, where the inventory's panel is) in the grey of its slots (139,
     * neutral, give or take). Measured on run 45822107616's frames: 40% with
     * the inventory open on every version, 4.5 to 12% in the world before.
     */
    private static double centreGrey(int[] rgb) {
        int w = 160, h = 90, hit = 0, n = 0;
        if (rgb.length < w * h * 3) return -1;
        for (int y = h * 30 / 100; y < h * 70 / 100; y++) {
            for (int x = w * 35 / 100; x < w * 65 / 100; x++) {
                int i = (y * w + x) * 3;
                int r = rgb[i], g = rgb[i + 1], b = rgb[i + 2];
                n++;
                if (Math.abs(r - 139) <= 12 && Math.abs(g - 139) <= 12 && Math.abs(b - 139) <= 12
                        && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) <= 8) hit++;
            }
        }
        return n == 0 ? -1 : hit / (double) n;
    }

    /** The mean difference of two pictures, 0 to 255. */
    private static double diff(int[] a, int[] b) {
        int n = Math.min(a.length, b.length);
        if (n == 0) return -1;
        long sum = 0;
        for (int i = 0; i < n; i++) sum += Math.abs(a[i] - b[i]);
        return sum / (double) n;
    }

    private static String runFf(List<String> cmd) throws Exception {
        List<String> c = new ArrayList<>(cmd);
        Process p = new ProcessBuilder(c).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor(120, TimeUnit.SECONDS);
        return out;
    }
}
