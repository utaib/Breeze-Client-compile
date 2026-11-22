package dev.breeze.devtest;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * The screen video in the self-test (RecorderCheck, phase 20): the MP4 the
 * first recording made, checked with ffprobe and against the game's own
 * screenshots, then the export editor on it.
 */
final class ScreenVideoCheck {

    static volatile String firstStats = "", firstResult;
    static volatile long firstStartNanos = -1;
    private static int sub;
    private static long subAt;
    private static dev.breeze.menu.ExportEditorScreen editor;

    private ScreenVideoCheck() {}

    static boolean step(Minecraft mc, long in, Path video, long invNanos, long pauseNanos, long plainNanos, double offFrameMs) {
        long since = System.currentTimeMillis() - subAt;
        switch (sub) {
            case 0 -> {
                FakeLauncherAudio.stop();
                if (video == null || !Files.isRegularFile(video)) {
                    AutoTest.report("rec-video", "problem", "no screen video: " + firstResult, "pass", "false");
                    sub = 99;
                    return true;
                }
                check(mc, video, invNanos, pauseNanos, plainNanos, offFrameMs);
                sub = 1;
                subAt = System.currentTimeMillis();
                return false;
            }
            case 1 -> {
                editor = new dev.breeze.menu.ExportEditorScreen(null, video);
                dev.breeze.compat.ActiveScreen.set(mc, editor);
                sub = 2;
                subAt = System.currentTimeMillis();
                return false;
            }
            case 2 -> {
                if (since < 4_000) return false;
                AutoTest.shot(mc, "export-editor");
                AutoTest.report("rec-editor-preview", "framesShown", String.valueOf(editor.framesShown()), "editor", editor.toString(),
                        "pass", String.valueOf(editor.framesShown() > 0));
                editor.testMute(1, true);
                editor.testExport();
                sub = 3;
                subAt = System.currentTimeMillis();
                return false;
            }
            case 3 -> {
                if (editor.busy() && since < 120_000) return false;
                Path out = editor.exported();
                List<JsonObject> st = out == null ? List.of() : streams(out, false);
                long audio = st.stream().filter(o -> "audio".equals(str(o, "codec_type"))).count();
                boolean stereo = st.stream().anyMatch(o -> "audio".equals(str(o, "codec_type")) && "2".equals(str(o, "channels")));
                boolean hasVideo = st.stream().anyMatch(o -> "video".equals(str(o, "codec_type")));
                boolean pass = out != null && hasVideo && audio == 1 && stereo;
                AutoTest.report("rec-editor", "state", String.valueOf(editor.state()), "file", String.valueOf(out), "streams", st.toString(),
                        "pass", String.valueOf(pass));
                dev.breeze.compat.ActiveScreen.set(mc, null);
                sub = 99;
                return true;
            }
            default -> {
                return true;
            }
        }
    }

    private static void check(Minecraft mc, Path video, long invNanos, long pauseNanos, long plainNanos, double offFrameMs) {
        JsonObject meta = null;
        try {
            meta = new Gson().fromJson(Files.readString(video.resolveSibling(video.getFileName() + ".json"), StandardCharsets.UTF_8), JsonObject.class);
        } catch (Exception ignored) {}
        List<JsonObject> st = streams(video, true);
        String format = run(List.of("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", video.toString())).trim();
        JsonObject vs = st.stream().filter(o -> "video".equals(str(o, "codec_type"))).findFirst().orElse(new JsonObject());
        long audio = st.stream().filter(o -> "audio".equals(str(o, "codec_type"))).count();
        long frames = parse(str(vs, "nb_read_frames"));
        String videoLine = str(vs, "codec_name") + " " + str(vs, "width") + "x" + str(vs, "height") + " " + str(vs, "r_frame_rate") + " " + frames + " frames";
        int fps = meta == null ? 0 : meta.get("fps").getAsInt();
        long metaFrames = meta == null ? -1 : meta.get("frames").getAsLong();
        String stats = firstStats == null ? "" : firstStats;
        double durationS = num(stats, "durationS"), recordedS = num(stats, "recordedS");
        boolean rateOk = (fps + "/1").equals(str(vs, "r_frame_rate"));
        boolean framesOk = frames > 0 && Math.abs(frames - metaFrames) <= 1 && Math.abs(frames - Math.round(recordedS * fps)) <= 2;
        boolean lengthOk = Math.abs(durationS - recordedS) <= 0.15 && Math.abs(parseD(format) - durationS) <= 0.2;
        // The menus' moments on the video's clock.
        Path shots = mc.gameDirectory.toPath().resolve("screenshots");
        String inv = compare(video, shots, "inventory", invNanos, plainNanos);
        String pause = compare(video, shots, "pause", pauseNanos, plainNanos);
        boolean pass = rateOk && framesOk && lengthOk && audio == 2 && inv.endsWith("pass") && pause.endsWith("pass");
        AutoTest.report("rec-video", "file", video.toAbsolutePath().toString(), "video", videoLine, "audioTracks", String.valueOf(audio),
                "frames", String.valueOf(frames), "framesInDescription", String.valueOf(metaFrames), "fps", String.valueOf(fps),
                "durationS", String.valueOf(durationS), "recordedS", String.valueOf(recordedS), "fileDurationS", format,
                "inventory", inv, "pauseMenu", pause, "rateOk", String.valueOf(rateOk), "framesOk", String.valueOf(framesOk),
                "lengthOk", String.valueOf(lengthOk), "pass", String.valueOf(pass));
        // What it cost and how even it is (software rendering on the build machines: comparable only between runs there).
        AutoTest.report("rec-video-cost", "renderThreadUsPerFrame", String.valueOf(num(stats, "renderThreadUsPerFrame")),
                "worstUs", String.valueOf(num(stats, "worstUs")), "mapUs", String.valueOf(num(stats, "mapUs")),
                "copyMs", String.valueOf(num(stats, "copyMs")), "encodeMs", String.valueOf(num(stats, "encodeMs")),
                "frameMsRecorderOff", String.format(Locale.ROOT, "%.2f", offFrameMs), "frameMsRecording", String.valueOf(num(stats, "gameFrameMs")),
                "frameSdMsRecording", String.valueOf(num(stats, "gameFrameSdMs")), "motionJitterMs", String.valueOf(num(stats, "motionJitterMs")),
                "slotErrorMaxMs", String.valueOf(num(stats, "slotErrorMaxMs")), "repeatedSlots", String.valueOf(num(stats, "repeatedSlots")),
                "skipped", String.valueOf(num(stats, "skipped")), "dropped", String.valueOf(num(stats, "dropped")), "stats", stats);
    }

    /**
     * The video's frame at a moment against the game's screenshot of that
     * moment and the plain screenshot (6 s, no menu): the frame must be
     * clearly closer to its own moment. "dOwn dPlain pass|fail".
     */
    private static String compare(Path video, Path shots, String name, long at, long plain) {
        try {
            if (at <= 0 || firstStartNanos <= 0) return "no moment fail";
            double t = (at - firstStartNanos) / 1e9;
            Path frame = shots.resolve("video-" + name + ".png");
            run(List.of("ffmpeg", "-v", "error", "-y", "-ss", String.format(Locale.ROOT, "%.3f", t), "-i", video.toString(), "-frames:v", "1", frame.toString()));
            BufferedImage f = javax.imageio.ImageIO.read(frame.toFile());
            BufferedImage own = javax.imageio.ImageIO.read(shots.resolve("rec-" + name + ".png").toFile());
            BufferedImage other = javax.imageio.ImageIO.read(shots.resolve("rec-6s.png").toFile());
            if (f == null || own == null || other == null) return "missing picture fail";
            double dOwn = diff(f, own), dOther = diff(f, other);
            boolean ok = dOwn < 30 && dOwn < dOther * 0.75;
            return String.format(Locale.ROOT, "t=%.2fs dOwn=%.1f dPlain=%.1f %s", t, dOwn, dOther, ok ? "pass" : "fail");
        } catch (Exception e) {
            return "error " + e + " fail";
        }
    }

    /** Mean difference of two pictures in grey at 128 by 72, 0 to 255. */
    private static double diff(BufferedImage a, BufferedImage b) {
        int w = 128, h = 72;
        double sum = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                sum += Math.abs(grey(a, x, y, w, h) - grey(b, x, y, w, h));
            }
        }
        return sum / (w * h);
    }

    private static double grey(BufferedImage img, int x, int y, int w, int h) {
        // A small box average, so a pixel's shift between the two does not count.
        int x0 = x * img.getWidth() / w, x1 = Math.max(x0 + 1, (x + 1) * img.getWidth() / w);
        int y0 = y * img.getHeight() / h, y1 = Math.max(y0 + 1, (y + 1) * img.getHeight() / h);
        double s = 0;
        int n = 0;
        for (int yy = y0; yy < y1; yy += 2) {
            for (int xx = x0; xx < x1; xx += 2) {
                int rgb = img.getRGB(xx, yy);
                s += 0.299 * ((rgb >> 16) & 0xFF) + 0.587 * ((rgb >> 8) & 0xFF) + 0.114 * (rgb & 0xFF);
                n++;
            }
        }
        return n == 0 ? 0 : s / n;
    }

    /** The file's streams as ffprobe sees them (JSON: its own field order does not matter). */
    private static List<JsonObject> streams(Path f, boolean count) {
        List<String> cmd = new ArrayList<>(List.of("ffprobe", "-v", "error"));
        if (count) cmd.add("-count_frames");
        cmd.addAll(List.of("-show_entries", "stream=codec_type,codec_name,width,height,r_frame_rate,nb_read_frames,channels", "-of", "json", f.toString()));
        List<JsonObject> out = new ArrayList<>();
        try {
            JsonObject o = new Gson().fromJson(run(cmd), JsonObject.class);
            for (com.google.gson.JsonElement e : o.getAsJsonArray("streams")) out.add(e.getAsJsonObject());
        } catch (Exception ignored) {}
        return out;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private static String run(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(new ArrayList<>(cmd)).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(60, TimeUnit.SECONDS);
            return out.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static long parse(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static double parseD(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static double num(String s, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:^|[ ,])" + key + "=(-?[0-9.]+)").matcher(s);
        return m.find() ? parseD(m.group(1)) : -1;
    }
}
