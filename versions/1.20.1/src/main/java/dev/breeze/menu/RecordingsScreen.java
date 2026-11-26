package dev.breeze.menu;

import com.mojang.blaze3d.platform.InputConstants;
import dev.breeze.compat.BreezeScreen;
import dev.breeze.compat.Widgets;
import dev.breeze.recorder.RecFormat;
import dev.breeze.recorder.RecMeta;
import dev.breeze.recorder.RecReader;
import dev.breeze.recorder.RecorderHooks;
import dev.breeze.recorder.Timeline;
import dev.breeze.recorder.ExportSettings;
import dev.breeze.shots.ScreenshotChat;
import dev.breeze.shots.ShotFiles;
import dev.breeze.shots.SystemOpen;
import dev.breeze.ui.Glass;
import dev.breeze.ui.Palette;
import dev.breeze.ui.Rect;
import dev.breeze.ui.Spacing;
import dev.breeze.ui.UiRender;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;

/**
 * Breeze's Recordings page: every recording in the game's recordings
 * folder, newest first, with its world, length, size and date. One is
 * chosen with a click; Watch, Export (a video into recordings/videos, with
 * the size, frame rate, quality and encoder chosen on the row above; the
 * frame rate goes from 30 to 240 fps, or "As recorded", the rate the player
 * had while recording, shown on each row),
 * Folder and Delete (with a confirmation) act on it.
 */
public class RecordingsScreen extends BreezeScreen {

    private static final int ROW_H = 24;

    private record Row(Path file, RecMeta meta, long size) {}

    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();
    private int selected = -1;
    private boolean confirmDelete;
    private double scroll;
    private String notice;
    private Rect panel = new Rect(0, 0, 0, 0);
    private Rect list = new Rect(0, 0, 0, 0);

    public RecordingsScreen(Screen parent) {
        super(Component.literal("Recordings"));
        this.parent = parent;
        load();
    }

    /** How many recordings are listed (self-test). */
    public int count() {
        return rows.size();
    }

    private void load() {
        rows.clear();
        Path dir = ScreenshotChat.recordingsDir();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(RecFormat.EXTENSION))::iterator) {
                try {
                    rows.add(new Row(p, RecReader.readMeta(p), Files.size(p)));
                } catch (Exception ignored) {
                    // Not a recording Breeze can read: not listed.
                }
            }
        } catch (Exception ignored) {
            // No folder yet: nothing recorded.
        }
        rows.sort(Comparator.comparingLong((Row r) -> r.meta().startedAt).reversed());
        if (selected >= rows.size()) selected = rows.size() - 1;
    }

    @Override
    protected void init() {
        panel = Rect.centered(this.width, this.height, Math.min(this.width - 30, 520), Math.max(180, this.height - 30));
        list = new Rect(panel.x + Spacing.MD, panel.y + 40, panel.w - Spacing.MD * 2, panel.h - 40 - 60);
        int y = panel.bottom() - 28, x = panel.x + Spacing.MD;
        boolean any = selected >= 0;
        if (confirmDelete && any) {
            addRenderableWidget(Widgets.button(Component.literal("Delete"), b -> deleteSelected(), x, y, 70, 20));
            addRenderableWidget(Widgets.button(Component.literal("Cancel"), b -> {
                confirmDelete = false;
                notice = null;
                rebuildWidgets();
            }, x + 76, y, 70, 20));
            return;
        }
        if (any) {
            addRenderableWidget(Widgets.button(Component.literal("Watch"), b -> run(RecorderHooks.watch(this.minecraft, rows.get(selected).file())), x, y, 60, 20));
            addRenderableWidget(Widgets.button(Component.literal("Export"), b -> run(RecorderHooks.export(this.minecraft,
                    rows.get(selected).file(), settings().fps, settings().quality)), x + 64, y, 60, 20));
            addRenderableWidget(Widgets.button(Component.literal("Folder"), b -> SystemOpen.reveal(rows.get(selected).file(),
                    r -> { if (!r.ok()) this.minecraft.execute(() -> notice = r.message()); }), x + 128, y, 60, 20));
            addRenderableWidget(Widgets.button(Component.literal("Delete"), b -> {
                confirmDelete = true;
                notice = "Delete " + rows.get(selected).file().getFileName() + "? This cannot be undone.";
                rebuildWidgets();
            }, x + 192, y, 60, 20));
        }
        addRenderableWidget(Widgets.button(Component.literal("Done"), b -> onClose(), panel.right() - Spacing.MD - 60, y, 60, 20));
        // What the Recorder key records (the screen video's frame rate, size and quality, and the world replay).
        addRenderableWidget(Widgets.button(Component.literal("Recorder preferences"), b ->
                dev.breeze.compat.ActiveScreen.set(this.minecraft, new RecorderSetupScreen(this, false)), panel.right() - Spacing.MD - 130, panel.y + 10, 130, 20));
        // Export settings: the video's size, frame rate, quality, and the graphics card's encoder.
        int sy = y - 24;
        ExportSettings es = settings();
        addRenderableWidget(Widgets.button(Component.literal("Size: " + es.sizeLabel()), b -> { es.nextSize(); rebuildWidgets(); }, x, sy, 96, 20));
        addRenderableWidget(Widgets.button(Component.literal(fpsLabel(es)), b -> { es.nextFps(); rebuildWidgets(); }, x + 100, sy, 112, 20));
        addRenderableWidget(Widgets.button(Component.literal("Quality: " + es.qualityLabel()), b -> { es.nextQuality(); rebuildWidgets(); }, x + 216, sy, 96, 20));
        addRenderableWidget(Widgets.button(Component.literal(es.hardware ? "Encoder: Graphics card" : "Encoder: Processor"),
                b -> { es.toggleHardware(); rebuildWidgets(); }, x + 316, sy, 132, 20));
    }

    /** "60 fps", or "As recorded" with the chosen recording's own rate: "As recorded: 198 fps". */
    private String fpsLabel(ExportSettings es) {
        if (es.fps != ExportSettings.AS_RECORDED) return es.fpsLabel();
        if (selected < 0 || selected >= rows.size()) return "As recorded";
        RecMeta m = rows.get(selected).meta();
        return m.fps > 0 ? "As recorded: " + ExportSettings.resolveFps(es.fps, m) + " fps" : "As recorded: 60 fps";
    }

    /** The frame rate setting's label (self-test). */
    public String fpsButton() {
        return fpsLabel(settings());
    }

    private ExportSettings settings() {
        return ExportSettings.get(this.minecraft.gameDirectory.toPath());
    }

    /** After Watch or Export: the page closes when it started, else says why. */
    private void run(String problem) {
        if (problem != null) {
            notice = problem;
        } else if (this.minecraft != null && dev.breeze.compat.ActiveScreen.get(this.minecraft) == this) {
            dev.breeze.compat.ActiveScreen.set(this.minecraft, null);
        }
    }

    private void deleteSelected() {
        Row r = rows.get(selected);
        try {
            Files.deleteIfExists(r.file());
            notice = "Deleted " + r.file().getFileName() + ".";
        } catch (Exception e) {
            notice = r.file().getFileName() + " could not be deleted.";
        }
        confirmDelete = false;
        selected = -1;
        load();
        rebuildWidgets();
    }

    private Rect row(int i) {
        return new Rect(list.x, list.y - (int) scroll + i * (ROW_H + 2), list.w, ROW_H);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Glass.scrim(g, this.width, this.height);
        Glass.panel(g, panel);
        g.drawString(this.font, "Recordings", panel.x + Spacing.MD, panel.y + 12, Palette.TEXT_PRIMARY, true);
        UiRender.textClipped(g, this.font, rows.isEmpty() ? "No recordings yet. Press the Recorder key in a world to record."
                : rows.size() + (rows.size() == 1 ? " recording" : " recordings") + ", newest first. Click one to choose it.",
                panel.x + Spacing.MD, panel.y + 25, panel.w - Spacing.MD * 2, Palette.TEXT_FAINT);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() * (ROW_H + 2) - list.h)));
        g.enableScissor(list.x, list.y, list.right(), list.bottom());
        SimpleDateFormat f = new SimpleDateFormat("d MMM yyyy, HH:mm");
        for (int i = 0; i < rows.size(); i++) {
            Rect r = row(i);
            if (r.bottom() < list.y || r.y > list.bottom()) continue;
            Row row = rows.get(i);
            boolean hover = r.contains(mouseX, mouseY);
            Glass.fillRounded(g, r.x, r.y, r.right(), r.bottom(), Glass.RADIUS_SM,
                    i == selected ? Palette.SURFACE_ACTIVE : hover ? Palette.SURFACE_HOVER : Palette.SURFACE);
            String name = row.file().getFileName().toString().replace(RecFormat.EXTENSION, "");
            String detail = row.meta().world + ", Minecraft " + row.meta().minecraft + "   " + Timeline.clock(row.meta().durationMs)
                    + (row.meta().fps > 0 ? "   " + Math.round(row.meta().fps) + " fps" : "")
                    + "   " + ShotFiles.size(row.size()) + "   " + f.format(new Date(row.meta().startedAt));
            UiRender.textClipped(g, this.font, name, r.x + 6, r.y + 3, r.w - 12, Palette.TEXT_PRIMARY);
            UiRender.textClipped(g, this.font, detail, r.x + 6, r.y + 13, r.w - 12, Palette.TEXT_SECONDARY);
            dev.breeze.devtest.Targets.put("recording-" + i, r.centerX(), r.centerY());
        }
        g.disableScissor();
        if (notice != null) UiRender.textClipped(g, this.font, notice, panel.x + Spacing.MD + 260, panel.bottom() - 22,
                Math.max(0, panel.w - Spacing.MD * 2 - 330), Palette.TEXT_SECONDARY);
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    protected boolean onMouseClicked(double mx, double my, int button) {
        if (superMouseClicked(mx, my, button)) return true;
        if (button == 0 && list.contains(mx, my)) {
            for (int i = 0; i < rows.size(); i++) {
                if (row(i).contains(mx, my)) {
                    selected = i;
                    confirmDelete = false;
                    notice = null;
                    rebuildWidgets();
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    protected boolean onMouseScrolled(double mx, double my, double dx, double dy) {
        scroll -= dy * 20;
        return true;
    }

    @Override
    protected boolean onKeyPressed(int key, int scanCode, int modifiers) {
        if (key == InputConstants.KEY_ESCAPE) {
            if (confirmDelete) {
                confirmDelete = false;
                notice = null;
                rebuildWidgets();
            } else {
                onClose();
            }
            return true;
        }
        return superKeyPressed(key, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) dev.breeze.compat.ActiveScreen.set(this.minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
