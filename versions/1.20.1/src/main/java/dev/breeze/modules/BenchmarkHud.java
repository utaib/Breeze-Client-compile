package dev.breeze.modules;

import dev.breeze.BreezeClient;
import dev.breeze.Category;
import dev.breeze.perf.BenchStats;
import dev.breeze.settings.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * A frame rate benchmark the player runs in their own world (2.29.0).
 *
 * Switched on, it waits until no screen is open, counts down, then times
 * every frame for the chosen length while it turns the camera twice round,
 * so every direction is drawn and two runs in the same place compare. At the
 * end it shows the average, the 1% low and the 0.1% low (BenchStats), says
 * them in chat with how they compare to the run before, writes them to
 * breeze/benchmarks in the game folder, and switches itself off.
 *
 * This is how a change is judged on a real computer: the build machines
 * have no graphics card, so no frame rate measured there means anything.
 * Run it before and after a change (a video preset, a mod, a setting), from
 * the same spot.
 *
 * It measures the frames the HUD is drawn in, so it stops if the HUD is
 * hidden or a screen is opened.
 */
public class BenchmarkHud extends AbstractHudModule {

    private static final String GROUP = "Benchmark";
    private static final boolean SELF_TEST = System.getProperty("breeze.autotest") != null;
    /** The most frames kept: an hour's worth is never needed, a minute at 2000 fps is. */
    private static final int MAX_FRAMES = 150_000;
    private static final long COUNTDOWN_NANOS = 3_000_000_000L;
    private static final long SHOWN_NANOS = 20_000_000_000L;
    private static volatile BenchStats lastResult;
    private static volatile Path lastFile;

    private final Setting.Mode length = add(new Setting.Mode("length", "Length", GROUP,
            new String[]{"15 seconds", "30 seconds", "60 seconds"}, 1));
    private final Setting.Bool turn = add(new Setting.Bool("turn", "Turn the camera", GROUP, true));

    private enum Stage { WAITING, COUNTDOWN, RUNNING, DONE, STOPPED }

    private Stage stage = Stage.WAITING;
    private long stageAt;
    private long lastFrameAt;
    private long[] frames;
    private int count;
    private float startYaw;
    private String why = "";
    private String line1 = "", line2 = "", line3 = "";
    private boolean finish;

    public BenchmarkHud() {
        super("Benchmark", Category.HUD,
                "Measures your frame rate for half a minute and saves the result. Run it before and after a change to see what the change did.",
                KEY_NONE, 4, 344);
    }

    /** Every frame is timed, so this one is not worked out 20 times a second like the others. */
    @Override
    protected boolean everyFrame() { return true; }

    @Override
    protected void onEnable() {
        stage = Stage.WAITING;
        stageAt = System.nanoTime();
        count = 0;
        finish = false;
        why = "";
    }

    @Override
    protected void onDisable() {
        if (stage == Stage.RUNNING) restoreCamera(Minecraft.getInstance());
        frames = null;
        stage = Stage.WAITING;
    }

    private long runNanos() {
        if (SELF_TEST) return 3_000_000_000L;
        return (length.index == 0 ? 15L : length.index == 2 ? 60L : 30L) * 1_000_000_000L;
    }

    @Override
    protected void onTick(Minecraft mc) {
        if (finish) {
            finish = false;
            setEnabled(false);
            return;
        }
        long now = System.nanoTime();
        boolean screen = dev.breeze.compat.ActiveScreen.get(mc) != null;
        boolean world = mc.level != null && mc.player != null;
        switch (stage) {
            case WAITING -> {
                if (world && !screen) {
                    stage = Stage.COUNTDOWN;
                    stageAt = now;
                }
            }
            case COUNTDOWN -> {
                if (!world || screen) {
                    stage = Stage.WAITING;
                } else if (SELF_TEST || now - stageAt >= COUNTDOWN_NANOS) {
                    frames = new long[MAX_FRAMES];
                    count = 0;
                    lastFrameAt = 0;
                    startYaw = mc.player.getYRot();
                    stage = Stage.RUNNING;
                    stageAt = now;
                }
            }
            case RUNNING -> {
                if (!world) {
                    stop(mc, "the world was left");
                } else if (screen) {
                    stop(mc, "a screen was opened");
                } else if (lastFrameAt != 0 && now - lastFrameAt > 2_000_000_000L) {
                    stop(mc, "the HUD is hidden");
                } else if (now - stageAt >= runNanos() || count >= MAX_FRAMES) {
                    done(mc);
                } else if (turn.value && !SELF_TEST) {
                    // Twice round over the run: every direction is drawn, and
                    // the view ends where it began.
                    float progress = (now - stageAt) / (float) runNanos();
                    mc.player.setYRot(startYaw + 720f * progress);
                }
            }
            case DONE, STOPPED -> {
                if (now - stageAt >= (SELF_TEST ? 4_000_000_000L : SHOWN_NANOS)) finish = true;
            }
        }
    }

    private void restoreCamera(Minecraft mc) {
        if (turn.value && !SELF_TEST && mc != null && mc.player != null) mc.player.setYRot(startYaw);
    }

    private void stop(Minecraft mc, String reason) {
        restoreCamera(mc);
        why = reason;
        frames = null;
        stage = Stage.STOPPED;
        stageAt = System.nanoTime();
        say(mc, "Benchmark stopped: " + reason + ". Switch it on again to run it.");
    }

    private void done(Minecraft mc) {
        restoreCamera(mc);
        BenchStats stats = BenchStats.of(frames, count);
        frames = null;
        stage = Stage.DONE;
        stageAt = System.nanoTime();
        if (stats == null) {
            line1 = "Benchmark: no frames were drawn";
            line2 = line3 = "";
            return;
        }
        double before = previousAverage(mc);
        String compared = stats.against(before);
        line1 = "Benchmark: " + BenchStats.whole(stats.avgFps) + " fps average";
        line2 = "1% low " + BenchStats.whole(stats.low1Fps) + ", 0.1% low " + BenchStats.whole(stats.low01Fps);
        line3 = compared.isEmpty() ? "" : capital(compared);
        lastResult = stats;
        lastFile = save(mc, stats);
        say(mc, "Breeze benchmark: " + stats.summary() + (compared.isEmpty() ? "" : ", " + compared)
                + (lastFile == null ? "" : ". Saved in breeze/benchmarks."));
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static void say(Minecraft mc, String text) {
        try {
            dev.breeze.compat.Game.message(mc, Component.literal(text));
        } catch (Throwable ignored) {
        }
    }

    private static Path folder(Minecraft mc) {
        return mc.gameDirectory.toPath().resolve("breeze").resolve("benchmarks");
    }

    /** The average of the run before this one, or 0 when there was none. */
    private static double previousAverage(Minecraft mc) {
        try {
            Path latest = folder(mc).resolve("latest.json");
            if (!Files.isRegularFile(latest)) return 0;
            com.google.gson.JsonObject o = new com.google.gson.Gson()
                    .fromJson(Files.readString(latest, StandardCharsets.UTF_8), com.google.gson.JsonObject.class);
            return o != null && o.has("avgFps") ? o.get("avgFps").getAsDouble() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** The graphics card as its driver names it, or "unknown" where it cannot be asked. */
    private static String renderer() {
        try {
            String name = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER);
            return name == null || name.isBlank() ? "unknown" : name;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static String quoted(String s) {
        return new com.google.gson.JsonPrimitive(s == null ? "" : s).toString();
    }

    private Path save(Minecraft mc, BenchStats stats) {
        try {
            Path dir = folder(mc);
            Files.createDirectories(dir);
            LocalDateTime at = LocalDateTime.now();
            String json = "{\"at\":" + quoted(at.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                    + "," + stats.jsonFields()
                    + ",\"cameraTurned\":" + (turn.value && !SELF_TEST)
                    + ",\"minecraft\":" + quoted(dev.breeze.compat.Platform.minecraftVersion())
                    + ",\"loader\":" + quoted(dev.breeze.compat.Platform.name())
                    + ",\"mods\":" + dev.breeze.compat.Platform.modCount()
                    + ",\"breeze\":" + quoted(dev.breeze.compat.Platform.modVersion("breeze"))
                    + ",\"window\":" + quoted(mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight())
                    + ",\"graphicsCard\":" + quoted(renderer())
                    + ",\"processorThreads\":" + Runtime.getRuntime().availableProcessors()
                    + ",\"memoryMb\":" + Runtime.getRuntime().maxMemory() / (1024 * 1024)
                    + ",\"java\":" + quoted(System.getProperty("java.version"))
                    + ",\"system\":" + quoted(System.getProperty("os.name"))
                    + "}\n";
            Path file = dir.resolve("benchmark-" + at.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".json");
            Files.writeString(file, json, StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("latest.json"), json, StandardCharsets.UTF_8);
            return file;
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] benchmark result not saved: {}", t.toString());
            return null;
        }
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        long now = System.nanoTime();
        switch (stage) {
            case WAITING -> line(g, font, "Benchmark: close the menu to start");
            case COUNTDOWN -> {
                long left = Math.max(0, COUNTDOWN_NANOS - (now - stageAt));
                line(g, font, "Benchmark starts in " + (left / 1_000_000_000L + 1));
            }
            case RUNNING -> {
                // This frame's time: since the frame before it was drawn.
                if (lastFrameAt != 0 && frames != null && count < frames.length) frames[count++] = now - lastFrameAt;
                lastFrameAt = now;
                long left = Math.max(0, runNanos() - (now - stageAt));
                line(g, font, "Benchmark: " + (left / 1_000_000_000L + 1) + " s left");
            }
            case DONE -> {
                line(g, font, line1, 0xFF55FF55);
                if (!line2.isEmpty()) line(g, font, line2);
                if (!line3.isEmpty()) line(g, font, line3);
            }
            case STOPPED -> line(g, font, "Benchmark stopped: " + why);
        }
    }

    /** For the self-test: the last run's numbers and where they were written. */
    public static BenchStats lastResult() { return lastResult; }

    public static Path lastFile() { return lastFile; }
}
