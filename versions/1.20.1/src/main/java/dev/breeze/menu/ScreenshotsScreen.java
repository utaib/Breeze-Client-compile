package dev.breeze.menu;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import dev.breeze.compat.BreezeScreen;
import dev.breeze.compat.Draw;
import dev.breeze.compat.Ids;
import dev.breeze.compat.Images;
import dev.breeze.compat.Widgets;
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
import net.minecraft.resources.ResourceLocation;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Breeze's Screenshots page: the game's screenshots folder as a grid, newest
 * first, and one picture large with Folder, Copy and Delete (with a
 * confirmation). Pictures are decoded and scaled on a background thread and
 * handed to the GPU a few per frame, so opening a folder of hundreds never
 * stalls the game; every texture is freed when the page closes.
 *
 * Escape goes from the large view back to the grid, and from the grid back to
 * where the page was opened from (the game, from chat), never further.
 */
public class ScreenshotsScreen extends BreezeScreen {

    private static final int THUMB_W = 128;
    private static final int THUMB_H = 72;
    private static final int CELL_W = THUMB_W + Spacing.SM;
    private static final int CELL_H = THUMB_H + 16 + Spacing.SM;
    /** The large view's picture is decoded at most this wide (enough for a 4K screen's panel). */
    private static final int LARGE_MAX = 1920;
    private static final int UPLOADS_PER_FRAME = 3;

    private record Decoded(String key, int w, int h, int[] argb) {}

    private record Tex(ResourceLocation id, int w, int h) {}

    private final Screen parent;
    private final String openFirst;
    private final Path dir = ScreenshotChat.dir();
    private List<ShotFiles.Shot> shots = new ArrayList<>();
    private final Map<String, Tex> textures = new HashMap<>();
    private final Map<String, Boolean> asked = new HashMap<>();
    private final ConcurrentLinkedQueue<Decoded> decoded = new ConcurrentLinkedQueue<>();
    private final ExecutorService decoder = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Breeze screenshots");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private int texCounter;

    private Rect panel = new Rect(0, 0, 0, 0);
    private Rect content = new Rect(0, 0, 0, 0);
    private double scroll;
    /** The picture shown large, or -1 for the grid. */
    private int large = -1;
    private boolean confirmDelete;
    private String notice;
    private int noticeColor = Palette.TEXT_SECONDARY;

    /** Opens the page from parent (null: back to the game); openFirst, when given, is shown large. */
    public ScreenshotsScreen(Screen parent, String openFirst) {
        super(Component.literal("Screenshots"));
        this.parent = parent;
        this.openFirst = openFirst;
        reload();
        if (openFirst != null) large = indexOf(openFirst);
    }

    /** What the page shows now (self-test): "grid", or "large <name>", with "confirm" while asking to delete. */
    public String state() {
        if (large < 0) return "grid " + shots.size();
        return "large " + shots.get(large).name() + (confirmDelete ? " confirm" : "");
    }

    private void reload() {
        shots = ShotFiles.list(dir);
    }

    private int indexOf(String name) {
        for (int i = 0; i < shots.size(); i++) if (shots.get(i).name().equals(name)) return i;
        return -1;
    }

    // ── Layout and buttons ─────────────────────────────────────────────────

    @Override
    protected void init() {
        panel = Rect.centered(this.width, this.height, Math.max(360, this.width - 40), Math.max(200, this.height - 24));
        content = new Rect(panel.x + Spacing.MD, panel.y + 40, panel.w - Spacing.MD * 2, panel.h - 40 - 36);
        int y = panel.bottom() - 28;
        int x = panel.x + Spacing.MD;
        if (large < 0) {
            addRenderableWidget(Widgets.button(Component.literal("Open the folder"), b -> openFolder(null), x, y, 110, 20));
            addRenderableWidget(Widgets.button(Component.literal("Done"), b -> onClose(), panel.right() - Spacing.MD - 70, y, 70, 20));
            return;
        }
        if (confirmDelete) {
            addRenderableWidget(Widgets.button(Component.literal("Delete"), b -> deleteShown(), x, y, 70, 20));
            addRenderableWidget(Widgets.button(Component.literal("Cancel"), b -> {
                confirmDelete = false;
                notice = null;
                rebuildWidgets();
            }, x + 76, y, 70, 20));
            return;
        }
        addRenderableWidget(Widgets.button(Component.literal("Back"), b -> back(), x, y, 60, 20));
        addRenderableWidget(Widgets.button(Component.literal("<"), b -> step(-1), x + 66, y, 20, 20));
        addRenderableWidget(Widgets.button(Component.literal(">"), b -> step(1), x + 90, y, 20, 20));
        int rx = panel.right() - Spacing.MD;
        addRenderableWidget(Widgets.button(Component.literal("Delete"), b -> {
            confirmDelete = true;
            notice = "Delete " + shots.get(large).name() + "? This cannot be undone.";
            noticeColor = Palette.WARN;
            rebuildWidgets();
        }, rx - 60, y, 60, 20));
        addRenderableWidget(Widgets.button(Component.literal("Copy"), b -> copyShown(), rx - 60 - 6 - 60, y, 60, 20));
        addRenderableWidget(Widgets.button(Component.literal("Folder"), b -> openFolder(shots.get(large).name()), rx - 60 - 6 - 60 - 6 - 60, y, 60, 20));
    }

    private void show(int index) {
        large = index;
        confirmDelete = false;
        notice = null;
        rebuildWidgets();
    }

    private void back() {
        show(-1);
    }

    private void step(int by) {
        if (shots.isEmpty() || large < 0) return;
        show(Math.floorMod(large + by, shots.size()));
    }

    /** The picture selected in the system's file manager, or with no name the folder itself. */
    private void openFolder(String name) {
        if (name == null) {
            try {
                Files.createDirectories(dir);
            } catch (Exception e) {
                say("The screenshots folder could not be made.", Palette.ERROR);
                return;
            }
            SystemOpen.openFolder(dir, r -> { if (!r.ok()) say(r.message(), Palette.ERROR); });
            return;
        }
        Path file = ShotFiles.resolve(dir, name);
        if (file == null) {
            say("That screenshot is no longer in the folder.", Palette.ERROR);
            return;
        }
        SystemOpen.reveal(file, r -> { if (!r.ok()) say(r.message(), Palette.ERROR); });
    }

    private void copyShown() {
        Path file = ShotFiles.resolve(dir, shots.get(large).name());
        if (file == null) {
            say("That screenshot is no longer in the folder.", Palette.ERROR);
            return;
        }
        say("Copying...", Palette.TEXT_SECONDARY);
        SystemOpen.copyImage(file, r -> {
            ScreenshotChat.event("copy-result " + r.ok() + " " + r.message());
            say(r.message(), r.ok() ? Palette.OK : Palette.ERROR);
        });
    }

    private void deleteShown() {
        String name = shots.get(large).name();
        Path file = ShotFiles.resolve(dir, name);
        boolean gone = false;
        try {
            gone = file != null && Files.deleteIfExists(file);
        } catch (Exception ignored) {}
        confirmDelete = false;
        if (!gone) {
            say(name + " could not be deleted.", Palette.ERROR);
            rebuildWidgets();
            return;
        }
        free(name + "#large");
        free(name + "#thumb");
        reload();
        large = shots.isEmpty() ? -1 : Math.min(large, shots.size() - 1);
        ScreenshotChat.event("deleted " + name);
        say("Deleted " + name + ".", Palette.TEXT_SECONDARY);
        rebuildWidgets();
    }

    /** A line at the bottom of the page; from any thread. */
    private void say(String text, int color) {
        if (this.minecraft == null) return;
        this.minecraft.execute(() -> {
            notice = text;
            noticeColor = color;
        });
    }

    // ── Pictures ───────────────────────────────────────────────────────────

    /** The texture for a picture at a size, asked for once and decoded in the background. */
    private Tex texture(ShotFiles.Shot s, boolean big) {
        String key = s.name() + (big ? "#large" : "#thumb");
        Tex t = textures.get(key);
        if (t != null || asked.containsKey(key)) return t;
        asked.put(key, true);
        Path file = dir.resolve(s.name());
        decoder.execute(() -> {
            Decoded d = decode(key, file, big ? LARGE_MAX : THUMB_W * 2, big ? LARGE_MAX : THUMB_H * 2);
            if (d != null) decoded.add(d);
        });
        return null;
    }

    /** The picture scaled to fit maxW x maxH (never up), as ARGB; null when it cannot be read. */
    private static Decoded decode(String key, Path file, int maxW, int maxH) {
        try {
            BufferedImage src = javax.imageio.ImageIO.read(file.toFile());
            if (src == null) return null;
            double s = Math.min(1.0, Math.min(maxW / (double) src.getWidth(), maxH / (double) src.getHeight()));
            int w = Math.max(1, (int) Math.round(src.getWidth() * s));
            int h = Math.max(1, (int) Math.round(src.getHeight() * s));
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, w, h, null);
            g.dispose();
            return new Decoded(key, w, h, out.getRGB(0, 0, w, h, null, 0, w));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Hands a few decoded pictures to the GPU (render thread). */
    private void upload() {
        for (int n = 0; n < UPLOADS_PER_FRAME; n++) {
            Decoded d = decoded.poll();
            if (d == null) return;
            if (!asked.containsKey(d.key())) continue;
            NativeImage img = new NativeImage(d.w(), d.h(), true);
            for (int y = 0; y < d.h(); y++) {
                for (int x = 0; x < d.w(); x++) Images.setPixelArgb(img, x, y, d.argb()[y * d.w() + x]);
            }
            ResourceLocation id = Ids.breeze("screenshots/" + (texCounter++));
            Images.register(id, img);
            Tex old = textures.put(d.key(), new Tex(id, d.w(), d.h()));
            if (old != null) release(old);
        }
    }

    private void free(String key) {
        asked.remove(key);
        Tex t = textures.remove(key);
        if (t != null) release(t);
    }

    private void release(Tex t) {
        if (this.minecraft != null) this.minecraft.getTextureManager().release(t.id());
    }

    // ── Drawing ────────────────────────────────────────────────────────────

    private Rect cell(int i, int cols) {
        int gridW = cols * CELL_W - Spacing.SM;
        int left = content.x + Math.max(0, (content.w - gridW) / 2);
        return new Rect(left + (i % cols) * CELL_W, content.y - (int) scroll + (i / cols) * CELL_H, THUMB_W, THUMB_H);
    }

    private int cols() {
        return Math.max(1, (content.w + Spacing.SM) / CELL_W);
    }

    private static String when(long ms) {
        return new SimpleDateFormat("d MMM yyyy, HH:mm").format(new Date(ms));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        upload();
        Glass.scrim(g, this.width, this.height);
        Glass.panel(g, panel);
        if (large >= 0 && large < shots.size()) renderLarge(g);
        else renderGrid(g, mouseX, mouseY);
        if (notice != null) {
            int left = large < 0 ? panel.x + Spacing.MD + 118 : confirmDelete ? panel.x + Spacing.MD + 154 : panel.x + Spacing.MD + 118;
            int right = large < 0 ? panel.right() - Spacing.MD - 78 : confirmDelete ? panel.right() - Spacing.MD : panel.right() - Spacing.MD - 198;
            UiRender.textClipped(g, this.font, notice, left, panel.bottom() - 22, Math.max(0, right - left), noticeColor);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void renderGrid(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(this.font, "Screenshots", panel.x + Spacing.MD, panel.y + 12, Palette.TEXT_PRIMARY, true);
        String count = shots.isEmpty() ? "No screenshots yet. Press F2 in game to take one."
                : shots.size() == 1 ? "1 picture, newest first" : shots.size() + " pictures, newest first";
        UiRender.textClipped(g, this.font, count, panel.x + Spacing.MD, panel.y + 25, panel.w - Spacing.MD * 2, Palette.TEXT_FAINT);
        int cols = cols();
        int rows = (shots.size() + cols - 1) / cols;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows * CELL_H - Spacing.SM - content.h)));
        g.enableScissor(content.x, content.y, content.right(), content.bottom());
        for (int i = 0; i < shots.size(); i++) {
            Rect c = cell(i, cols);
            if (c.y + CELL_H < content.y || c.y > content.bottom()) continue;
            ShotFiles.Shot s = shots.get(i);
            boolean hover = c.contains(mouseX, mouseY) && content.contains(mouseX, mouseY);
            Glass.fillRounded(g, c.x - 1, c.y - 1, c.right() + 1, c.bottom() + 1, Glass.RADIUS_SM,
                    hover ? Palette.ACCENT : Palette.SURFACE);
            Tex t = texture(s, false);
            if (t != null) fit(g, t, c);
            UiRender.textClipped(g, this.font, s.name(), c.x, c.bottom() + 4, c.w, hover ? Palette.TEXT_PRIMARY : Palette.TEXT_SECONDARY);
            dev.breeze.devtest.Targets.put("shot-" + i, c.centerX(), c.centerY());
        }
        g.disableScissor();
    }

    private void renderLarge(GuiGraphics g) {
        ShotFiles.Shot s = shots.get(large);
        UiRender.textClipped(g, this.font, s.name(), panel.x + Spacing.MD, panel.y + 12, panel.w - Spacing.MD * 2 - 80, Palette.TEXT_PRIMARY);
        UiRender.textClipped(g, this.font, when(s.modified()) + "  " + ShotFiles.size(s.size()) + "  " + (large + 1) + " of " + shots.size(),
                panel.x + Spacing.MD, panel.y + 25, panel.w - Spacing.MD * 2, Palette.TEXT_FAINT);
        Tex t = texture(s, true);
        if (t == null) {
            Tex thumb = textures.get(s.name() + "#thumb");
            if (thumb != null) fit(g, thumb, content);
            else UiRender.textCentered(g, this.font, "Loading...", content, Palette.TEXT_FAINT);
        } else {
            fit(g, t, content);
        }
        // The pictures either side, so stepping through them is instant.
        if (shots.size() > 1) {
            texture(shots.get(Math.floorMod(large + 1, shots.size())), true);
            texture(shots.get(Math.floorMod(large - 1, shots.size())), true);
        }
    }

    /** Draws a picture as large as fits in box, centred, keeping its shape. */
    private static void fit(GuiGraphics g, Tex t, Rect box) {
        double s = Math.min(box.w / (double) t.w(), box.h / (double) t.h());
        int w = Math.max(1, (int) (t.w() * s));
        int h = Math.max(1, (int) (t.h() * s));
        int x = box.x + (box.w - w) / 2;
        int y = box.y + (box.h - h) / 2;
        Draw.blit(g, t.id(), x, y, w, h, 0f, 0f, t.w(), t.h(), t.w(), t.h());
    }

    // ── Input ──────────────────────────────────────────────────────────────

    @Override
    protected boolean onMouseClicked(double mx, double my, int button) {
        if (superMouseClicked(mx, my, button)) return true;
        if (large < 0 && button == 0 && content.contains(mx, my)) {
            int cols = cols();
            for (int i = 0; i < shots.size(); i++) {
                if (cell(i, cols).contains(mx, my)) {
                    show(i);
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    protected boolean onMouseScrolled(double mx, double my, double dx, double dy) {
        if (large >= 0) {
            if (dy != 0) step(dy > 0 ? -1 : 1);
            return true;
        }
        scroll -= dy * 24;
        return true;
    }

    @Override
    protected boolean onKeyPressed(int key, int scanCode, int modifiers) {
        if (key == InputConstants.KEY_ESCAPE) {
            if (confirmDelete) {
                confirmDelete = false;
                notice = null;
                rebuildWidgets();
            } else if (large >= 0) {
                back();
            } else {
                onClose();
            }
            return true;
        }
        if (large >= 0 && !confirmDelete) {
            if (key == InputConstants.KEY_LEFT) {
                step(-1);
                return true;
            }
            if (key == InputConstants.KEY_RIGHT) {
                step(1);
                return true;
            }
        }
        return superKeyPressed(key, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) dev.breeze.compat.ActiveScreen.set(this.minecraft, parent);
    }

    @Override
    public void removed() {
        decoder.shutdownNow();
        decoded.clear();
        for (Tex t : textures.values()) release(t);
        textures.clear();
        asked.clear();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
