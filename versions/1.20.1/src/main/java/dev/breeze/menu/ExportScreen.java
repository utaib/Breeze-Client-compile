package dev.breeze.menu;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import dev.breeze.compat.BreezeScreen;
import dev.breeze.compat.Draw;
import dev.breeze.compat.Ids;
import dev.breeze.compat.Images;
import dev.breeze.compat.Widgets;
import dev.breeze.recorder.Ffmpeg;
import dev.breeze.recorder.RecFormat;
import dev.breeze.recorder.Timeline;
import dev.breeze.recorder.VideoMix;
import dev.breeze.recorder.VideoPreview;
import dev.breeze.recorder.VideoProbe;
import dev.breeze.shots.SystemOpen;
import dev.breeze.ui.Glass;
import dev.breeze.ui.Palette;
import dev.breeze.ui.Rect;
import dev.breeze.ui.Spacing;
import dev.breeze.ui.UiRender;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The export editor (1.2.3, round 3): opened by Export in chat or on the
 * Recordings page. The screen video plays at the top (frames decoded by
 * ffmpeg into a texture, sound mixed at the volumes below), with a seek
 * bar. Game and microphone volume each go from 0 to 200% and mute; Export
 * makes an MP4 in the videos folder with the picture as recorded and the
 * two tracks mixed as heard, with progress, then Open and Folder. When the
 * recording kept its world replay, a resource pack can be chosen from the
 * installed ones and Render draws the world again with it, frame by frame
 * at the export settings' rate and size (menus and the inventory are only
 * in the screen video). While exporting, only Cancel stops it: Escape and
 * other keys do nothing.
 */
public class ExportScreen extends BreezeScreen {

    private static final int VOL_STEP = 10, VOL_MAX = 200;
    private static final ResourceLocation TEX = Ids.breeze("export_preview");

    private final Screen parent;
    /** The screen video, or null when the recording has none (only its world replay). */
    private final Path video;
    /** The world replay, or null when it was not kept. */
    private final Path recording;
    private final String ffmpeg;
    private VideoProbe info;
    private String problem;

    private int gameVol = 100, micVol = 100;
    private boolean gameMute, micMute;
    private double position;
    private boolean playing;
    private VideoPreview preview;
    private long seenSeq;
    private boolean textureMade;
    private int texW, texH;
    private long restartAt;

    private VideoMix export;
    private Path exported;
    private String notice;

    private final List<String[]> packs = new ArrayList<>();
    private int pack;

    private Rect panel = new Rect(0, 0, 0, 0);
    private Rect view = new Rect(0, 0, 0, 0);
    private Rect bar = new Rect(0, 0, 0, 0);

    /** The screen shown now (self-test), or null. */
    public static volatile ExportScreen shown;

    public ExportScreen(Screen parent, Path video, Path recording) {
        super(Component.literal("Export"));
        this.parent = parent;
        this.video = video != null && Files.isRegularFile(video) ? video : null;
        this.recording = recording != null && Files.isRegularFile(recording) ? recording : null;
        this.ffmpeg = Ffmpeg.find();
        if (ffmpeg == null) problem = Ffmpeg.request();
        else if (this.video != null) {
            info = VideoProbe.of(ffmpeg, this.video);
            if (info == null) problem = "That video could not be read.";
        }
        packs.add(new String[]{"", "Packs on now"});
        if (this.recording != null) {
            try {
                com.google.gson.JsonObject list = dev.breeze.packs.PackMenu.json(Minecraft.getInstance(), false);
                for (com.google.gson.JsonElement el : list.getAsJsonArray("packs")) {
                    com.google.gson.JsonObject p = el.getAsJsonObject();
                    if (p.get("locked").getAsBoolean() || !p.get("folder").getAsBoolean()) continue;
                    packs.add(new String[]{p.get("id").getAsString(), p.get("title").getAsString()});
                }
            } catch (Throwable ignored) {
                // No pack list: only the packs on now.
            }
        }
    }

    /** The editor for a screen video in the videos folder, with its world replay when one has the same name. */
    public static ExportScreen forVideo(Screen parent, Path video) {
        String stem = video.getFileName().toString().replaceAll("\\.mp4$", "");
        Path rec = video.toAbsolutePath().getParent().getParent().resolve(stem + RecFormat.EXTENSION);
        return new ExportScreen(parent, video, rec);
    }

    /** The editor for a world replay, with its screen video when one has the same name. */
    public static ExportScreen forRecording(Screen parent, Path rec) {
        String stem = rec.getFileName().toString().replace(RecFormat.EXTENSION, "");
        Path video = rec.toAbsolutePath().getParent().resolve("videos").resolve(stem + ".mp4");
        return new ExportScreen(parent, video, rec);
    }

    @Override
    protected void init() {
        shown = this;
        panel = Rect.centered(this.width, this.height, Math.min(this.width - 20, 560), Math.min(this.height - 16, 340));
        int x = panel.x + Spacing.MD, w = panel.w - Spacing.MD * 2;
        int controls = recording != null ? 112 : 88;
        int viewH = Math.max(40, panel.h - 34 - controls - 18);
        view = new Rect(x, panel.y + 30, w, viewH);
        bar = new Rect(x, view.bottom() + 4, w, 6);
        int y = bar.bottom() + 6;
        if (export != null && !export.done()) {
            // Exporting: only Cancel.
            addRenderableWidget(Widgets.button(Component.literal("Cancel"), b -> export.cancel(), panel.right() - Spacing.MD - 70, panel.bottom() - 28, 70, 20));
            return;
        }
        boolean canPlay = info != null;
        net.minecraft.client.gui.components.Button play = Widgets.button(Component.literal(playing ? "Pause" : "Play"), b -> togglePlay(), x, y, 54, 20);
        play.active = canPlay;
        addRenderableWidget(play);
        int vx = x + 60;
        boolean hasGame = info != null && info.gameTrack() >= 0, hasMic = info != null && info.micTrack() >= 0;
        vx = volumeRow(vx, y, "Game", hasGame, true);
        volumeRow(vx + 8, y, "Mic", hasMic, false);
        y += 24;
        if (recording != null) {
            String label = "Pack: " + packs.get(pack)[1];
            if (label.length() > 30) label = label.substring(0, 29) + "...";
            addRenderableWidget(Widgets.button(Component.literal(label), b -> {
                pack = (pack + 1) % packs.size();
                rebuildWidgets();
            }, x, y, 200, 20));
            addRenderableWidget(Widgets.button(Component.literal("Render"), b -> render(), x + 204, y, 70, 20));
            y += 24;
        }
        int by = panel.bottom() - 28;
        net.minecraft.client.gui.components.Button exp = Widgets.button(Component.literal("Export"), b -> startExport(), x, by, 70, 20);
        exp.active = info != null;
        addRenderableWidget(exp);
        if (exported != null) {
            addRenderableWidget(Widgets.button(Component.literal("Open"), b -> SystemOpen.openFile(exported, this::opened), x + 74, by, 56, 20));
            addRenderableWidget(Widgets.button(Component.literal("Folder"), b -> SystemOpen.reveal(exported, this::opened), x + 134, by, 56, 20));
        }
        addRenderableWidget(Widgets.button(Component.literal("Close"), b -> onClose(), panel.right() - Spacing.MD - 60, by, 60, 20));
    }

    private void opened(SystemOpen.Result r) {
        if (!r.ok() && this.minecraft != null) this.minecraft.execute(() -> notice = r.message());
    }

    /** A track's controls: its name, Mute, minus, the volume, plus. Returns the x after it. */
    private int volumeRow(int x, int y, String name, boolean has, boolean game) {
        int vol = game ? gameVol : micVol;
        boolean mute = game ? gameMute : micMute;
        net.minecraft.client.gui.components.Button m = Widgets.button(Component.literal(!has ? name + ": none" : mute ? name + ": Muted" : name + ": " + vol + "%"), b -> {
            if (game) gameMute = !gameMute;
            else micMute = !micMute;
            volumeChanged();
        }, x, y, 96, 20);
        m.active = has;
        addRenderableWidget(m);
        net.minecraft.client.gui.components.Button minus = Widgets.button(Component.literal("-"), b -> {
            if (game) gameVol = Math.max(0, gameVol - VOL_STEP);
            else micVol = Math.max(0, micVol - VOL_STEP);
            volumeChanged();
        }, x + 98, y, 20, 20);
        net.minecraft.client.gui.components.Button plus = Widgets.button(Component.literal("+"), b -> {
            if (game) gameVol = Math.min(VOL_MAX, gameVol + VOL_STEP);
            else micVol = Math.min(VOL_MAX, micVol + VOL_STEP);
            volumeChanged();
        }, x + 120, y, 20, 20);
        minus.active = plus.active = has && !mute;
        addRenderableWidget(minus);
        addRenderableWidget(plus);
        return x + 142;
    }

    private void volumeChanged() {
        rebuildWidgets();
        // Heard in the preview a moment later (several clicks make one restart).
        if (playing) restartAt = System.currentTimeMillis() + 250;
    }

    /** The game track's volume as exported: 0 when muted, else 0 to 2. */
    public double gameVolume() {
        return gameMute ? 0 : gameVol / 100.0;
    }

    public double micVolume() {
        return micMute ? 0 : micVol / 100.0;
    }

    private void togglePlay() {
        if (info == null) return;
        if (playing) {
            stopPreview();
            playing = false;
        } else {
            if (position >= info.seconds() - 0.1) position = 0;
            startPreview();
            playing = true;
        }
        rebuildWidgets();
    }

    private void startPreview() {
        stopPreview();
        if (info == null || ffmpeg == null) return;
        int maxW = Math.max(64, Math.min(640, view.w * 2)), maxH = Math.max(36, Math.min(360, view.h * 2));
        preview = new VideoPreview(ffmpeg, video, info, position, gameVolume(), micVolume(), maxW, maxH);
        seenSeq = 0;
    }

    private void stopPreview() {
        VideoPreview p = preview;
        if (p != null) {
            position = p.position();
            p.close();
        }
        preview = null;
    }

    private void seek(double fraction) {
        if (info == null) return;
        position = Math.max(0, Math.min(info.seconds(), fraction * info.seconds()));
        if (playing) startPreview();
        else {
            // One frame of the new place, then held.
            startPreview();
            singleFrame = true;
        }
    }

    private boolean singleFrame;

    /** Export: the picture as it is, the tracks mixed at the chosen volumes. */
    public void startExport() {
        if (info == null || export != null && !export.done()) return;
        if (playing) togglePlay();
        exported = null;
        notice = null;
        export = VideoMix.start(ffmpeg, video, info, gameVolume(), micVolume());
        rebuildWidgets();
    }

    /** The running or last export (self-test). */
    public VideoMix export() {
        return export;
    }

    /** The preview playing (self-test), or null. */
    public VideoPreview preview() {
        return preview;
    }

    /** Pictures of the preview drawn so far (self-test). */
    public int texturesMade;

    /** A key press as the screen gets it (self-test). */
    public void testKey(int key) {
        onKeyPressed(key, 0, 0);
    }

    /** Sets the volumes as the buttons would (self-test). */
    public void testVolumes(boolean muteGame, int game, boolean muteMic, int mic) {
        gameMute = muteGame;
        gameVol = game;
        micMute = muteMic;
        micVol = mic;
        rebuildWidgets();
    }

    /** Packs offered for Render, by id ("" for the packs on now) (self-test). */
    public List<String> packIds() {
        List<String> out = new ArrayList<>();
        for (String[] p : packs) out.add(p[0]);
        return out;
    }

    /** Chooses the pack for Render (self-test). */
    public void choosePack(String id) {
        for (int i = 0; i < packs.size(); i++) if (packs.get(i)[0].equals(id)) pack = i;
        rebuildWidgets();
    }

    /** Render: leaves this screen and draws the world replay again with the chosen pack (RenderJob). */
    public void render() {
        if (recording == null || this.minecraft == null) return;
        stopPreview();
        playing = false;
        String id = packs.get(pack)[0];
        String why = RenderJob.start(this.minecraft, recording, id.isEmpty() ? null : id, video);
        if (why != null) {
            notice = why;
            return;
        }
        shown = null;
        dev.breeze.compat.ActiveScreen.set(this.minecraft, null);
    }

    @Override
    public void tick() {
        super.tick();
        if (restartAt > 0 && System.currentTimeMillis() >= restartAt) {
            restartAt = 0;
            if (playing) startPreview();
        }
        VideoPreview p = preview;
        if (p != null && playing && p.ended()) {
            position = info.seconds();
            stopPreview();
            playing = false;
            rebuildWidgets();
        }
        if (export != null && export.done() && exported == null && notice == null) {
            if (export.result() == null) {
                exported = export.out();
                notice = "Saved " + exported.getFileName() + ".";
            } else {
                notice = export.result();
            }
            rebuildWidgets();
        }
    }

    /** Puts the newest preview frame in the texture (render thread). */
    private void upload() {
        VideoPreview p = preview;
        if (p == null) return;
        byte[] f = p.frameAfter(seenSeq);
        if (f == null) return;
        seenSeq = p.seq();
        int w = p.width(), h = p.height();
        NativeImage img = new NativeImage(w, h, false);
        for (int y = 0, i = 0; y < h; y++) {
            for (int x = 0; x < w; x++, i += 4) {
                int argb = 0xFF000000 | (f[i] & 0xFF) << 16 | (f[i + 1] & 0xFF) << 8 | (f[i + 2] & 0xFF);
                Images.setPixelArgb(img, x, y, argb);
            }
        }
        Images.register(TEX, img);
        textureMade = true;
        texW = w;
        texH = h;
        texturesMade++;
        if (singleFrame) {
            singleFrame = false;
            double keep = position;
            p.close();
            preview = null;
            position = keep;
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        upload();
        Glass.scrim(g, this.width, this.height);
        Glass.panel(g, panel);
        int x = panel.x + Spacing.MD, w = panel.w - Spacing.MD * 2;
        String name = video != null ? video.getFileName().toString() : recording != null ? recording.getFileName().toString() : "";
        g.drawString(this.font, "Export", x, panel.y + 10, Palette.TEXT_PRIMARY, true);
        UiRender.textClipped(g, this.font, name, x + 44, panel.y + 10, w - 44, Palette.TEXT_FAINT);
        g.fill(view.x, view.y, view.right(), view.bottom(), 0xFF000000);
        if (textureMade) {
            double s = Math.min(view.w / (double) texW, view.h / (double) texH);
            int dw = Math.max(1, (int) (texW * s)), dh = Math.max(1, (int) (texH * s));
            int dx = view.x + (view.w - dw) / 2, dy = view.y + (view.h - dh) / 2;
            Draw.blit(g, TEX, dx, dy, dw, dh, 0f, 0f, texW, texH, texW, texH);
        } else {
            String msg = problem != null ? problem : video == null ? "This recording has no screen video. Render makes one from its world replay."
                    : "Press Play to watch it.";
            UiRender.textClipped(g, this.font, msg, view.x + 8, view.y + view.h / 2 - 4, view.w - 16, Palette.TEXT_SECONDARY);
        }
        // The seek bar and the time.
        double len = info == null ? 0 : info.seconds();
        double pos = preview != null ? preview.position() : position;
        g.fill(bar.x, bar.y, bar.right(), bar.bottom(), Palette.SURFACE);
        if (len > 0) g.fill(bar.x, bar.y, bar.x + (int) (bar.w * Math.min(1, pos / len)), bar.bottom(), Palette.ACCENT);
        int textY = bar.bottom() + 6 + 6;
        if (export != null && !export.done()) {
            int pct = (int) Math.round(export.progress() * 100);
            Rect pr = new Rect(x, panel.bottom() - 52, w, 8);
            g.fill(pr.x, pr.y, pr.right(), pr.bottom(), Palette.SURFACE);
            g.fill(pr.x, pr.y, pr.x + pr.w * pct / 100, pr.bottom(), Palette.ACCENT);
            UiRender.textClipped(g, this.font, "Exporting " + pct + "%. Only Cancel stops it.", x, panel.bottom() - 22, w - 80, Palette.TEXT_SECONDARY);
        } else {
            UiRender.textClipped(g, this.font, Timeline.clock(Math.round(pos * 1000)) + " / " + Timeline.clock(Math.round(len * 1000)),
                    panel.right() - Spacing.MD - 90, textY - 16 + 24 + 6, 90, Palette.TEXT_FAINT);
            if (recording != null) {
                UiRender.textClipped(g, this.font, "Render draws the world again with the pack. Menus and inventory are only in the screen video.",
                        x, textY + 24 + 24, w, Palette.TEXT_FAINT);
            }
            if (notice != null) UiRender.textClipped(g, this.font, notice, x + 196, panel.bottom() - 22, Math.max(0, w - 196 - 66), Palette.TEXT_SECONDARY);
        }
        if (preview != null && preview.audioProblem() != null) {
            UiRender.textClipped(g, this.font, "The preview has no sound here (" + preview.audioProblem() + "). Export still mixes it.",
                    view.x + 4, view.bottom() - 11, view.w - 8, Palette.TEXT_SECONDARY);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    protected boolean onMouseClicked(double mx, double my, int button) {
        if (superMouseClicked(mx, my, button)) return true;
        boolean busy = export != null && !export.done();
        if (!busy && button == 0 && mx >= bar.x && mx <= bar.right() && my >= bar.y - 3 && my <= bar.bottom() + 3) {
            seek((mx - bar.x) / Math.max(1.0, bar.w));
            return true;
        }
        return false;
    }

    @Override
    protected boolean onKeyPressed(int key, int scanCode, int modifiers) {
        // While exporting, only Cancel stops it: keys do nothing.
        if (export != null && !export.done()) return true;
        if (key == InputConstants.KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (key == InputConstants.KEY_SPACE) {
            togglePlay();
            return true;
        }
        return superKeyPressed(key, scanCode, modifiers);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return export == null || export.done();
    }

    @Override
    public void onClose() {
        if (export != null && !export.done()) return;
        stopPreview();
        shown = null;
        if (textureMade && this.minecraft != null) this.minecraft.getTextureManager().release(TEX);
        textureMade = false;
        if (this.minecraft != null) dev.breeze.compat.ActiveScreen.set(this.minecraft, parent);
    }

    @Override
    public void removed() {
        // Another screen took over (or the game closed it): nothing keeps playing behind it.
        stopPreview();
        playing = false;
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Where the editor is, for the log and the self-test. */
    public String describe() {
        return String.format(Locale.ROOT, "video=%s recording=%s tracks=%s game=%.2f mic=%.2f packs=%d textures=%d",
                video == null ? "" : video.getFileName(), recording == null ? "" : recording.getFileName(),
                info == null ? "?" : info.tracks(), gameVolume(), micVolume(), packs.size(), texturesMade);
    }
}
