package dev.breeze.menu;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import dev.breeze.BreezeClient;
import dev.breeze.compat.BreezeScreen;
import dev.breeze.compat.Draw;
import dev.breeze.compat.Ids;
import dev.breeze.compat.Images;
import dev.breeze.compat.Widgets;
import dev.breeze.recorder.ExportSettings;
import dev.breeze.recorder.Ffmpeg;
import dev.breeze.recorder.Mux;
import dev.breeze.recorder.RecorderHooks;
import dev.breeze.recorder.Timeline;
import dev.breeze.shots.ScreenshotChat;
import dev.breeze.shots.SystemOpen;
import dev.breeze.ui.Glass;
import dev.breeze.ui.Palette;
import dev.breeze.ui.Rect;
import dev.breeze.ui.Spacing;
import dev.breeze.ui.UiRender;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * The export editor, opened with Export after a recording (chat) or from
 * Recordings: the screen video plays as a preview, the game sound and the
 * microphone each have a volume and a mute, a resource pack from the
 * player's own can be chosen to render the recording again from its world
 * replay (smooth, at the chosen frame rate and size; menus and the
 * inventory are only in the screen video), and Export writes the result as
 * an MP4 in the videos folder, with Open folder after.
 *
 * The preview is decoded by ffmpeg into small frames shown as a texture,
 * and its sound played by Java at the chosen volumes, so it works in every
 * version and needs no browser codec.
 */
public class ExportEditorScreen extends BreezeScreen {

    private static final int PREVIEW_W = 426;
    private static final int PREVIEW_FPS = 20;
    private static final ResourceLocation TEXTURE = Ids.breeze("export-preview");

    private final Screen parent;
    private final Path video;
    private Path rendered;
    private final String id;
    private String replay;
    private int vw = 16, vh = 9;
    private long durationMs;
    private double videoStartMs;
    private final List<String> tracks = new ArrayList<>();
    private String soundNote;
    private final double[] volume = {1.0, 1.0};
    private final boolean[] muted = {false, false};
    private final List<String[]> packs = new ArrayList<>();
    private int pack = -1;
    private String notice;
    private volatile String exportState;
    private volatile Path exported;
    private volatile boolean exporting;
    private Rect panel = new Rect(0, 0, 0, 0), box = new Rect(0, 0, 0, 0), seek = new Rect(0, 0, 0, 0);
    private final Preview preview = new Preview();

    /** For the self-test: the last editor opened. */
    public static volatile ExportEditorScreen last;

    public ExportEditorScreen(Screen parent, Path video) {
        this(parent, video, null);
    }

    /** rendered: a render of this video's world replay with another pack, which Export then uses as the picture. */
    public ExportEditorScreen(Screen parent, Path video, Path rendered) {
        super(Component.literal("Export"));
        this.parent = parent;
        this.video = video;
        this.rendered = rendered != null && Files.isRegularFile(rendered) ? rendered : null;
        String n = video.getFileName().toString();
        this.id = n.endsWith(".mp4") ? n.substring(0, n.length() - 4) : n;
        readDescription();
        last = this;
    }

    private void readDescription() {
        try {
            Path f = video.resolveSibling(video.getFileName() + ".json");
            if (!Files.isRegularFile(f)) return;
            JsonObject o = new Gson().fromJson(Files.readString(f, StandardCharsets.UTF_8), JsonObject.class);
            vw = num(o, "width", vw);
            vh = num(o, "height", vh);
            durationMs = num(o, "durationMs", 0);
            videoStartMs = o.has("videoStartMs") ? o.get("videoStartMs").getAsDouble() : 0;
            String t = str(o, "tracks");
            for (String s : t.split(",")) if (!s.isBlank()) tracks.add(s.trim());
            replay = str(o, "replay");
            if (replay.isEmpty() || !Files.isRegularFile(video.getParent().getParent().resolve(replay))) replay = null;
            soundNote = str(o, "soundNote");
            if (soundNote.isEmpty()) soundNote = null;
        } catch (Exception e) {
            BreezeClient.LOGGER.debug("[Breeze] export editor: {}", e.toString());
        }
    }

    private static int num(JsonObject o, String k, int d) {
        JsonElement e = o.get(k);
        try {
            return e == null ? d : e.getAsInt();
        } catch (Exception x) {
            return d;
        }
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    /** The volume of track i as it goes into the export (0 when muted). */
    private double vol(int i) {
        return muted[i] ? 0 : volume[i];
    }

    private double[] volumes() {
        double[] v = new double[tracks.size()];
        for (int i = 0; i < v.length; i++) v[i] = vol(trackIndex(tracks.get(i)));
        return v;
    }

    /** 0 for the game sound, 1 for the microphone. */
    private static int trackIndex(String name) {
        return "mic".equals(name) ? 1 : 0;
    }

    private boolean has(int which) {
        for (String t : tracks) if (trackIndex(t) == which) return true;
        return false;
    }

    /** The right-hand column of controls beside the preview: its left edge and width, and a row's top. */
    private int colX, colW;

    private int row(int i) {
        return box.y + i * 23;
    }

    @Override
    protected void init() {
        if (packs.isEmpty()) packs.addAll(dev.breeze.packs.PackMenu.folderPacks(this.minecraft));
        panel = Rect.centered(this.width, this.height, Math.min(this.width - 16, 470), Math.min(this.height - 12, 236));
        int x = panel.x + Spacing.MD, w = panel.w - Spacing.MD * 2;
        // The preview on the left, its controls on the right; notes, state and buttons below.
        int pw = Math.max(120, (int) (w * 0.48));
        int ph = Math.max(60, Math.min(pw * vh / Math.max(1, vw), panel.h - 110));
        pw = Math.min(pw, ph * vw / Math.max(1, vh));
        box = new Rect(x, panel.y + 24, pw, ph);
        seek = new Rect(box.x, box.bottom() + 3, box.w, 6);
        colX = box.right() + 8;
        colW = panel.right() - Spacing.MD - colX;
        addRenderableWidget(Widgets.button(Component.literal(preview.playing() ? "Pause" : "Play"), b -> {
            if (preview.playing()) preview.pause();
            else preview.play(preview.positionMs() >= durationMs - 100 ? 0 : preview.positionMs());
            rebuildWidgets();
        }, colX, row(0), 48, 20));
        volumeRow(0, row(1));
        volumeRow(1, row(2));
        Button pk = addRenderableWidget(Widgets.button(Component.literal("Pack: " + packLabel()), b -> {
            pack = pack + 1 >= packs.size() ? -1 : pack + 1;
            rebuildWidgets();
        }, colX, row(3), colW, 20));
        pk.active = replay != null && !packs.isEmpty();
        ExportSettings es = ExportSettings.get(this.minecraft.gameDirectory.toPath());
        int half = (colW - 4) / 2;
        Button size = addRenderableWidget(Widgets.button(Component.literal(es.sizeLabel()), b -> { es.nextSize(); rebuildWidgets(); }, colX, row(4), half, 20));
        Button rate = addRenderableWidget(Widgets.button(Component.literal(es.fps == ExportSettings.AS_RECORDED ? "As played" : es.fps + " fps"),
                b -> { es.nextFps(); rebuildWidgets(); }, colX + half + 4, row(4), colW - half - 4, 20));
        size.active = rate.active = replay != null;
        int by = panel.bottom() - 26;
        Button render = addRenderableWidget(Widgets.button(Component.literal("Render"), b -> render(), x, by, 56, 20));
        render.active = replay != null && pack >= 0 && !exporting;
        Button export = addRenderableWidget(Widgets.button(Component.literal("Export"), b -> export(), x + 60, by, 56, 20));
        export.active = !exporting;
        addRenderableWidget(Widgets.button(Component.literal("Open folder"), b -> SystemOpen.reveal(exported != null ? exported : video,
                r -> { if (!r.ok()) this.minecraft.execute(() -> notice = r.message()); }), x + 120, by, 72, 20));
        addRenderableWidget(Widgets.button(Component.literal("Done"), b -> onClose(), panel.right() - Spacing.MD - 50, by, 50, 20));
        if (!preview.started) preview.play(0);
    }

    /** A track's label is drawn in render; here its -, + and Mute at the right of the column. */
    private void volumeRow(int i, int y) {
        boolean on = has(i);
        int bx = colX + colW - 84;
        Button minus = addRenderableWidget(Widgets.button(Component.literal("-"), b -> { volume[i] = Math.max(0, Math.round(volume[i] * 10 - 1) / 10.0); changed(); }, bx, y, 18, 20));
        Button plus = addRenderableWidget(Widgets.button(Component.literal("+"), b -> { volume[i] = Math.min(2, Math.round(volume[i] * 10 + 1) / 10.0); changed(); }, bx + 20, y, 18, 20));
        Button mute = addRenderableWidget(Widgets.button(Component.literal(muted[i] ? "Unmute" : "Mute"), b -> { muted[i] = !muted[i]; changed(); }, bx + 40, y, 44, 20));
        minus.active = plus.active = mute.active = on;
    }

    private void changed() {
        preview.soundChanged();
        rebuildWidgets();
    }

    private String packLabel() {
        if (replay == null) return "As recorded";
        return pack < 0 || pack >= packs.size() ? "As recorded" : packs.get(pack)[1];
    }

    // ── Render and export ──────────────────────────────────────────────────

    private void render() {
        if (replay == null || pack < 0) return;
        preview.stop();
        Path rec = video.getParent().getParent().resolve(replay);
        Path out = video.resolveSibling("." + id + ".render.mp4");
        ExportSettings es = ExportSettings.get(this.minecraft.gameDirectory.toPath());
        Path v = video;
        String why = RecorderHooks.render(this.minecraft, rec, es.fps, es.size, es.quality, packs.get(pack)[0], out, problem -> {
            ExportEditorScreen next = new ExportEditorScreen(null, v, problem == null ? out : null);
            next.notice = problem == null ? "Rendered with " + packLabel() + ". Export to save it with the sound." : problem;
            next.pack = pack;
            dev.breeze.compat.ActiveScreen.set(this.minecraft, next);
        });
        if (why != null) notice = why;
    }

    private void export() {
        String ffmpeg = Ffmpeg.find();
        if (ffmpeg == null) {
            notice = Ffmpeg.request();
            return;
        }
        Path dir = video.getParent();
        String base = id + (rendered != null ? "_render" : "_export");
        Path out = dir.resolve(base + ".mp4");
        for (int i = 1; Files.exists(out); i++) out = dir.resolve(base + "_" + i + ".mp4");
        Path picture = rendered != null ? rendered : video;
        // A render starts at the recording's start; the screen video (and its sound) a moment later.
        double offset = rendered != null ? videoStartMs / 1000.0 : 0;
        List<String> cmd = Mux.edit(ffmpeg, picture, tracks.isEmpty() ? null : video, volumes(), offset, out);
        exporting = true;
        exportState = "Exporting...";
        rebuildWidgets();
        Path target = out;
        Thread t = new Thread(() -> {
            String err = run(cmd);
            this.minecraft.execute(() -> {
                exporting = false;
                if (err == null) {
                    exported = target;
                    exportState = "Saved: " + target.getFileName();
                    ScreenshotChat.event("export-saved " + target.getFileName());
                } else {
                    exportState = "The export failed: " + err;
                }
                BreezeClient.LOGGER.info("[Breeze] export editor: {}", exportState);
                if (dev.breeze.compat.ActiveScreen.get(this.minecraft) == this) rebuildWidgets();
            });
        }, "Breeze export editor");
        t.setDaemon(true);
        t.start();
    }

    private static String run(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            byte[] o = p.getInputStream().readAllBytes();
            if (!p.waitFor(600, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "ffmpeg did not finish";
            }
            if (p.exitValue() == 0) return null;
            String e = new String(o, StandardCharsets.UTF_8).trim();
            return e.isEmpty() ? "ffmpeg failed" : e.lines().reduce((a, b) -> b).orElse("ffmpeg failed");
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    /** The last export's file, or null (self-test). */
    public Path exported() {
        return exported;
    }

    /** Self-test controls: mute a track (0 game, 1 microphone) and export. */
    public void testMute(int which, boolean on) {
        muted[which] = on;
    }

    public void testExport() {
        export();
    }

    public boolean busy() {
        return exporting;
    }

    public String state() {
        return exportState;
    }

    public int framesShown() {
        return preview.shown;
    }

    // ── Drawing ────────────────────────────────────────────────────────────

    @Override
    public void tick() {
        super.tick();
        preview.upload(this.minecraft);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        preview.upload(this.minecraft);
        Glass.scrim(g, this.width, this.height);
        Glass.panel(g, panel);
        int x = panel.x + Spacing.MD, w = panel.w - Spacing.MD * 2;
        g.drawString(this.font, "Export " + id, x, panel.y + 8, Palette.TEXT_PRIMARY, true);
        g.fill(box.x, box.y, box.right(), box.bottom(), 0xFF000000);
        if (preview.textureW > 0) {
            Draw.blit(g, TEXTURE, box.x, box.y, box.w, box.h, 0f, 0f, preview.textureW, preview.textureH, preview.textureW, preview.textureH);
        } else {
            UiRender.textClipped(g, this.font, preview.problem != null ? preview.problem : "Loading the preview...", box.x + 6, box.y + box.h / 2 - 4, box.w - 12, Palette.TEXT_SECONDARY);
        }
        long pos = preview.positionMs();
        g.fill(seek.x, seek.y + 2, seek.right(), seek.y + 4, Palette.BORDER_HOVER);
        if (durationMs > 0) g.fill(seek.x, seek.y + 2, seek.x + (int) (seek.w * Math.min(1.0, pos / (double) durationMs)), seek.y + 4, Palette.ACCENT);
        UiRender.textClipped(g, this.font, Timeline.clock(pos) + " / " + Timeline.clock(durationMs), colX + 54, row(0) + 6, colW - 54, Palette.TEXT_SECONDARY);
        volumeLabel(g, 0, "Game", colX, row(1));
        volumeLabel(g, 1, "Mic", colX, row(2));
        int ny = Math.max(seek.bottom(), row(5)) + 4;
        String note = replay == null
                ? "No world replay was kept with this video, so it cannot be rendered with another pack."
                : rendered != null ? "Rendered with the pack: Export uses the render, with the sound from the screen video."
                : "Render redraws the world from the replay with the pack: smooth, at the size and frame rate above.";
        UiRender.textClipped(g, this.font, note, x, ny, w, Palette.TEXT_FAINT);
        UiRender.textClipped(g, this.font, "Menus, chat, the HUD and the inventory are only in the screen video, not in a render.", x, ny + 10, w, Palette.TEXT_FAINT);
        String bottom = exportState != null ? exportState : notice;
        if (tracks.isEmpty() && bottom == null) bottom = "This video has no sound" + (soundNote != null ? ": " + soundNote : ".");
        if (bottom != null) UiRender.textClipped(g, this.font, bottom, x, panel.bottom() - 38, w, Palette.TEXT_SECONDARY);
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void volumeLabel(GuiGraphics g, int i, String label, int x, int y) {
        String v = !has(i) ? "not recorded" : muted[i] ? "muted" : Math.round(volume[i] * 100) + "%";
        UiRender.textClipped(g, this.font, label + ": " + v, x, y + 6, Math.max(10, colW - 88), has(i) ? Palette.TEXT_PRIMARY : Palette.TEXT_FAINT);
    }

    @Override
    protected boolean onMouseClicked(double mx, double my, int button) {
        if (superMouseClicked(mx, my, button)) return true;
        if (button == 0 && durationMs > 0 && mx >= seek.x && mx <= seek.right() && my >= seek.y - 3 && my <= seek.bottom() + 3) {
            long at = (long) (durationMs * Math.max(0, Math.min(1, (mx - seek.x) / Math.max(1.0, seek.w))));
            preview.play(at);
            rebuildWidgets();
            return true;
        }
        return false;
    }

    @Override
    protected boolean onKeyPressed(int key, int scanCode, int modifiers) {
        if (key == InputConstants.KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (key == InputConstants.KEY_SPACE) {
            if (preview.playing()) preview.pause();
            else preview.play(preview.positionMs());
            rebuildWidgets();
            return true;
        }
        return superKeyPressed(key, scanCode, modifiers);
    }

    @Override
    public void removed() {
        preview.stop();
        super.removed();
    }

    @Override
    public void onClose() {
        preview.stop();
        if (this.minecraft != null) {
            this.minecraft.getTextureManager().release(TEXTURE);
            dev.breeze.compat.ActiveScreen.set(this.minecraft, parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ── The preview ────────────────────────────────────────────────────────

    /** The screen video decoded by ffmpeg into small frames, and its sound through Java, from a position. */
    private final class Preview {
        private final BlockingQueue<byte[]> frames = new ArrayBlockingQueue<>(3);
        private Process video, sound;
        private volatile javax.sound.sampled.SourceDataLine line;
        private int fw, fh;
        private long startMs, startNanos;
        private boolean playing, started;
        private long pausedAt;
        private long frameIndex;
        int textureW, textureH, shown;
        volatile String problem;
        private int generation;

        boolean playing() {
            return playing;
        }

        long positionMs() {
            if (!playing) return pausedAt;
            return Math.min(durationMs, startMs + (System.nanoTime() - startNanos) / 1_000_000L);
        }

        void play(long atMs) {
            stop();
            started = true;
            String ffmpeg = Ffmpeg.find();
            if (ffmpeg == null) {
                problem = "The preview needs ffmpeg, which the Breeze launcher gets.";
                return;
            }
            fw = PREVIEW_W & ~1;
            fh = Math.max(2, (int) Math.round(PREVIEW_W * (double) vh / Math.max(1, vw)) & ~1);
            startMs = atMs;
            startNanos = System.nanoTime();
            frameIndex = 0;
            playing = true;
            int gen = ++generation;
            Thread t = new Thread(() -> runVideo(ffmpeg, atMs, gen), "Breeze preview video");
            t.setDaemon(true);
            t.start();
            startSound(ffmpeg, atMs, gen);
        }

        void pause() {
            pausedAt = positionMs();
            stop();
        }

        void stop() {
            if (playing) pausedAt = positionMs();
            playing = false;
            generation++;
            if (video != null) video.destroy();
            if (sound != null) sound.destroy();
            video = null;
            sound = null;
            javax.sound.sampled.SourceDataLine l = line;
            line = null;
            if (l != null) {
                try {
                    l.stop();
                    l.close();
                } catch (Throwable ignored) {}
            }
            frames.clear();
        }

        void soundChanged() {
            if (!playing) return;
            String ffmpeg = Ffmpeg.find();
            if (ffmpeg == null) return;
            if (sound != null) sound.destroy();
            javax.sound.sampled.SourceDataLine l = line;
            line = null;
            if (l != null) {
                try {
                    l.close();
                } catch (Throwable ignored) {}
            }
            startSound(ffmpeg, positionMs(), generation);
        }

        private void runVideo(String ffmpeg, long atMs, int gen) {
            try {
                Process p = new ProcessBuilder(Mux.preview(ffmpeg, video(), atMs / 1000.0, fw, fh, PREVIEW_FPS)).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                if (gen != generation) {
                    p.destroy();
                    return;
                }
                this.video = p;
                try (InputStream in = p.getInputStream()) {
                    int size = fw * fh * 4;
                    while (gen == generation) {
                        byte[] f = in.readNBytes(size);
                        if (f.length < size) break;
                        while (gen == generation && !frames.offer(f, 100, java.util.concurrent.TimeUnit.MILLISECONDS)) { }
                    }
                }
            } catch (Throwable t) {
                problem = "The preview could not be played: " + t.getMessage();
            }
        }

        private Path video() {
            return ExportEditorScreen.this.video;
        }

        private void startSound(String ffmpeg, long atMs, int gen) {
            if (tracks.isEmpty()) return;
            List<String> cmd = Mux.previewSound(ffmpeg, ExportEditorScreen.this.video, atMs / 1000.0, volumes());
            if (cmd == null) return;
            Thread t = new Thread(() -> {
                try {
                    javax.sound.sampled.AudioFormat fmt = new javax.sound.sampled.AudioFormat(44100f, 16, 2, true, false);
                    javax.sound.sampled.SourceDataLine l = javax.sound.sampled.AudioSystem.getSourceDataLine(fmt);
                    l.open(fmt, 44100);
                    Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                    if (gen != generation) {
                        p.destroy();
                        l.close();
                        return;
                    }
                    sound = p;
                    line = l;
                    l.start();
                    byte[] buf = new byte[8192];
                    try (InputStream in = p.getInputStream()) {
                        int n;
                        while (gen == generation && line == l && (n = in.read(buf)) > 0) l.write(buf, 0, n);
                    }
                } catch (Throwable e) {
                    // No sound device (or none Java can use): the preview plays without sound.
                    BreezeClient.LOGGER.debug("[Breeze] preview sound: {}", e.toString());
                }
            }, "Breeze preview sound");
            t.setDaemon(true);
            t.start();
        }

        /** Shows the frame due now (render thread). */
        void upload(net.minecraft.client.Minecraft mc) {
            if (!playing) return;
            long due = (System.nanoTime() - startNanos) * PREVIEW_FPS / 1_000_000_000L;
            byte[] f = null;
            while (frameIndex <= due) {
                byte[] next = frames.poll();
                if (next == null) break;
                f = next;
                frameIndex++;
            }
            if (f == null) {
                if (positionMs() >= durationMs && durationMs > 0 && frames.isEmpty() && (video == null || !video.isAlive())) {
                    pausedAt = durationMs;
                    stop();
                    if (dev.breeze.compat.ActiveScreen.get(mc) == ExportEditorScreen.this) rebuildWidgets();
                }
                return;
            }
            NativeImage img = new NativeImage(fw, fh, false);
            for (int y = 0; y < fh; y++) {
                int row = y * fw * 4;
                for (int x = 0; x < fw; x++) {
                    int i = row + x * 4;
                    Images.setPixelArgb(img, x, y, 0xFF000000 | (f[i] & 0xFF) << 16 | (f[i + 1] & 0xFF) << 8 | (f[i + 2] & 0xFF));
                }
            }
            Images.register(TEXTURE, img);
            textureW = fw;
            textureH = fh;
            shown++;
        }
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "ExportEditorScreen[%s tracks=%s replay=%s]", id, tracks, replay);
    }
}
