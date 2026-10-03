package dev.breeze.menu;

import com.mojang.blaze3d.platform.InputConstants;
import dev.breeze.Module;
import dev.breeze.ModuleManager;
import dev.breeze.compat.BreezeScreen;
import dev.breeze.compat.Widgets;
import dev.breeze.config.BreezeConfig;
import dev.breeze.hud.HudPlacement;
import dev.breeze.modules.AbstractHudModule;
import dev.breeze.ui.HudLayout;
import dev.breeze.ui.Palette;
import dev.breeze.ui.Theme;
import dev.breeze.ui.UiRender;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The HUD editor. It moves the real HUD: every enabled HUD module is drawn by
 * its own renderer, and what is dragged here is where it will be in game.
 *
 * Click an element to select it, drag it (it snaps to the screen's edges and
 * centre and to other elements, with guide lines), scroll or press + and - to
 * scale it, the arrow keys to nudge it a pixel, Delete to put it back where it
 * started. The side list switches HUD elements on and off. Done (or Escape or
 * Enter) keeps the changes; Cancel puts every position and scale back as they
 * were when the editor opened. Positions are stored anchored to the nearest
 * screen edges (HudPlacement), so they survive a new window size or GUI scale,
 * and an element can never be left off screen.
 */
public class HudEditorScreen extends BreezeScreen {

    private static final int SNAP = 4;
    private static final int LIST_W = 120;
    /** Where the element list starts in the panel, below the buttons. */
    private static final int LIST_TOP = 66;
    private static final int ROW_H = 12;

    private final Screen parent;
    /** Positions and scales when the editor opened, for Cancel. */
    private final Map<String, HudPlacement> startPlacement = new HashMap<>();
    private final Map<String, Integer> startScale = new HashMap<>();
    private final Map<String, Boolean> startEnabled = new HashMap<>();

    private AbstractHudModule selected;
    private AbstractHudModule dragging;
    private int dragOffX;
    private int dragOffY;
    private boolean snap = true;
    private List<Integer> guidesX = List.of();
    private List<Integer> guidesY = List.of();
    private int listScroll;

    private boolean panelShown = true;
    private Button showPanel;
    private Button snapButton;
    private Button smaller;
    private Button bigger;
    private Button resetOne;
    private Button settings;

    public HudEditorScreen(Screen parent) {
        super(Component.literal("HUD Editor"));
        this.parent = parent;
        for (AbstractHudModule h : hudModules()) {
            startPlacement.put(h.getName(), h.getPlacement());
            startScale.put(h.getName(), h.style().scale.value);
            startEnabled.put(h.getName(), h.isEnabled());
        }
    }

    private static List<AbstractHudModule> hudModules() {
        List<AbstractHudModule> out = new ArrayList<>();
        for (Module m : ModuleManager.getModules()) {
            if (m instanceof AbstractHudModule h) out.add(h);
        }
        return out;
    }

    private static List<AbstractHudModule> shown() {
        List<AbstractHudModule> out = new ArrayList<>();
        for (AbstractHudModule h : hudModules()) {
            if (h.isEnabled()) out.add(h);
        }
        return out;
    }

    // ── Layout ───────────────────────────────────────────────────────────

    @Override
    protected void init() {
        clearHudButtons();
        if (!panelShown) {
            // Collapsed: one small button in the bottom-right corner.
            showPanel = addRenderableWidget(Widgets.button(Component.literal("Panel"), b -> setPanel(true),
                    this.width - 44, this.height - 18, 40, 14));
            return;
        }
        int lx = this.width - LIST_W + 4;
        int bw = (LIST_W - 12) / 2;
        int bh = 14;
        addRenderableWidget(Widgets.button(Component.literal("Done"), b -> done(), lx, 4, bw, bh));
        addRenderableWidget(Widgets.button(Component.literal("Cancel"), b -> cancel(), lx + bw + 4, 4, bw, bh));
        addRenderableWidget(Widgets.button(Component.literal("Arrange"), b -> arrange(), lx, 20, bw, bh));
        addRenderableWidget(Widgets.button(Component.literal("Reset all"), b -> resetAll(), lx + bw + 4, 20, bw, bh));
        snapButton = addRenderableWidget(Widgets.button(snapLabel(), b -> {
            snap = !snap;
            b.setMessage(snapLabel());
        }, lx, 36, bw, bh));
        addRenderableWidget(Widgets.button(Component.literal("Hide"), b -> setPanel(false), lx + bw + 4, 36, bw, bh));

        int by = this.height - 36;
        smaller = addRenderableWidget(Widgets.button(Component.literal("Smaller"), b -> scaleSelected(-10), lx, by, bw, bh));
        bigger = addRenderableWidget(Widgets.button(Component.literal("Bigger"), b -> scaleSelected(10), lx + bw + 4, by, bw, bh));
        resetOne = addRenderableWidget(Widgets.button(Component.literal("Reset"), b -> resetSelected(), lx, by + 16, bw, bh));
        settings = addRenderableWidget(Widgets.button(Component.literal("Settings"), b -> {
            if (selected != null && selected.hasSettings()) {
                dev.breeze.compat.ActiveScreen.set(this.minecraft, new ModuleSettingsScreen(this, selected));
            }
        }, lx + bw + 4, by + 16, bw, bh));
    }

    /** Shows or hides the side panel; hidden, the whole screen is HUD. */
    private void setPanel(boolean shown) {
        panelShown = shown;
        clearWidgets();
        init();
    }

    private void clearHudButtons() {
        snapButton = null;
        smaller = null;
        bigger = null;
        resetOne = null;
        settings = null;
        showPanel = null;
    }

    /** Where the HUD can be edited: the screen, less the panel when it is shown. */
    private int areaW() {
        return panelShown ? this.width - LIST_W : this.width;
    }

    private Component snapLabel() {
        return Component.literal(snap ? "Snap: On" : "Snap: Off");
    }

    // ── Drawing ──────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Light enough to see the world behind, dark enough to read the frames.
        g.fill(0, 0, this.width, this.height, Palette.alpha(Palette.BG_DEEP, 0x60));

        ModuleManager.renderAll(g, partialTick);

        for (AbstractHudModule h : shown()) {
            int x = h.getHudX();
            int y = h.getHudY();
            int w = h.getHudW();
            int hh = h.getHudH();
            boolean over = inside(mouseX, mouseY, x, y, w, hh);
            boolean sel = h == selected;
            if (sel || over) g.fill(x - 1, y - 1, x + w + 1, y + hh + 1, Palette.BORDER_HOVER);
            UiRender.border(g, x - 1, y - 1, w + 2, hh + 2,
                    sel ? Theme.primary() : over ? Palette.alpha(0xFFFFFFFF, 0xA0) : Palette.alpha(0xFFFFFFFF, 0x50));
            if (overlapsAnother(h)) UiRender.border(g, x - 2, y - 2, w + 4, hh + 4, Palette.alpha(Palette.WARN, 0xB0));
            dev.breeze.devtest.Targets.put("hud-" + h.getName(), x + w / 2, y + hh / 2);
        }

        int guide = Palette.alpha(Theme.primary(), 0xB0);
        for (int gx : guidesX) g.fill(gx, 0, gx + 1, this.height, guide);
        for (int gy : guidesY) g.fill(0, gy, areaW(), gy + 1, guide);

        if (panelShown) {
            drawList(g, mouseX, mouseY);
            boolean has = selected != null;
            smaller.active = has && selected.style().scale.value > selected.style().scale.min;
            bigger.active = has && selected.style().scale.value < selected.style().scale.max;
            resetOne.active = has;
            settings.active = has && selected.hasSettings();
        }
        drawStatus(g);

        super.render(g, mouseX, mouseY, partialTick);
    }

    private void drawStatus(GuiGraphics g) {
        String line;
        if (selected != null) {
            line = selected.getName() + "  " + selected.style().scale.value + "%  at "
                    + selected.getHudX() + ", " + selected.getHudY();
        } else if (shown().isEmpty()) {
            line = "No HUD elements are on. Switch some on in the list.";
        } else {
            line = "Click an element to select it. Drag to move, scroll to resize.";
        }
        int w = this.font.width(line);
        int cx = areaW() / 2;
        g.drawString(this.font, line, cx - w / 2, this.height - 12, Palette.TEXT_PRIMARY, true);
    }

    private void drawList(GuiGraphics g, int mouseX, int mouseY) {
        int lx = this.width - LIST_W;
        g.fill(lx, 0, this.width, this.height, Palette.alpha(Palette.BG, 0xE0));
        g.fill(lx, 0, lx + 1, this.height, Palette.BORDER_HOVER);
        g.drawString(this.font, "HUD elements", lx + 6, LIST_TOP - 12, Palette.TEXT_SECONDARY, false);
        List<AbstractHudModule> all = hudModules();
        int top = LIST_TOP;
        int visible = (this.height - 40 - top) / ROW_H;
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, all.size() - visible)));
        g.enableScissor(lx, top, this.width, top + visible * ROW_H);
        for (int i = listScroll; i < all.size() && i - listScroll < visible; i++) {
            AbstractHudModule h = all.get(i);
            int ry = top + (i - listScroll) * ROW_H;
            boolean over = mouseX >= lx && mouseY >= ry && mouseY < ry + ROW_H;
            if (h == selected) g.fill(lx + 1, ry, this.width, ry + ROW_H, Palette.SURFACE_ACTIVE);
            else if (over) g.fill(lx + 1, ry, this.width, ry + ROW_H, Palette.SURFACE_HOVER);
            UiRender.toggle(g, lx + 6, ry + 2, 14, 8, h.isEnabled());
            UiRender.textClipped(g, this.font, h.getName(), lx + 24, ry + 2, LIST_W - 28,
                    h.isEnabled() ? Palette.TEXT_PRIMARY : Palette.TEXT_FAINT);
        }
        g.disableScissor();
    }

    // ── Input ────────────────────────────────────────────────────────────

    @Override
    protected boolean onMouseClicked(double mx, double my, int button) {
        if (superMouseClicked(mx, my, button)) return true;
        if (button != 0) return false;
        if (panelShown && mx >= this.width - LIST_W) {
            AbstractHudModule h = listRow(my);
            if (h != null) {
                h.toggle();
                selected = h.isEnabled() ? h : (selected == h ? null : selected);
            }
            return true;
        }
        // Topmost first: the last drawn is on top.
        List<AbstractHudModule> list = shown();
        for (int i = list.size() - 1; i >= 0; i--) {
            AbstractHudModule h = list.get(i);
            if (inside(mx, my, h.getHudX(), h.getHudY(), h.getHudW(), h.getHudH())) {
                selected = h;
                dragging = h;
                dragOffX = (int) mx - h.getHudX();
                dragOffY = (int) my - h.getHudY();
                return true;
            }
        }
        selected = null;
        return true;
    }

    private AbstractHudModule listRow(double my) {
        int visible = (this.height - 40 - LIST_TOP) / ROW_H;
        if (my < LIST_TOP || my >= LIST_TOP + visible * ROW_H) return null;
        int i = (int) ((my - LIST_TOP) / ROW_H) + listScroll;
        List<AbstractHudModule> all = hudModules();
        return i >= 0 && i < all.size() ? all.get(i) : null;
    }

    @Override
    protected boolean onMouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging == null) return superMouseDragged(mx, my, button, dx, dy);
        int w = dragging.getHudW();
        int h = dragging.getHudH();
        int nx = (int) mx - dragOffX;
        int ny = (int) my - dragOffY;
        int areaW = areaW();
        if (snap) {
            HudPlacement.Snap s = HudPlacement.snap(nx, ny, w, h, areaW, this.height, othersThan(dragging), SNAP);
            nx = s.x();
            ny = s.y();
            guidesX = s.guidesX();
            guidesY = s.guidesY();
        } else {
            guidesX = List.of();
            guidesY = List.of();
        }
        dragging.setHudPos(nx, ny);
        return true;
    }

    @Override
    protected boolean onMouseReleased(double mx, double my, int button) {
        if (dragging != null) {
            dragging = null;
            guidesX = List.of();
            guidesY = List.of();
            return true;
        }
        return superMouseReleased(mx, my, button);
    }

    @Override
    protected boolean onMouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (panelShown && mx >= this.width - LIST_W) {
            listScroll -= (int) Math.signum(scrollY) * 2;
            return true;
        }
        for (AbstractHudModule h : shown()) {
            if (inside(mx, my, h.getHudX(), h.getHudY(), h.getHudW(), h.getHudH())) {
                selected = h;
                scaleSelected(scrollY > 0 ? 10 : -10);
                return true;
            }
        }
        return superMouseScrolled(mx, my, scrollX, scrollY);
    }

    @Override
    protected boolean onKeyPressed(int key, int scanCode, int modifiers) {
        if (key == InputConstants.KEY_ESCAPE || key == InputConstants.KEY_RETURN) {
            done();
            return true;
        }
        if (key == InputConstants.KEY_H) {
            setPanel(!panelShown);
            return true;
        }
        if (key == InputConstants.KEY_TAB) {
            List<AbstractHudModule> list = shown();
            if (!list.isEmpty()) {
                int i = selected == null ? -1 : list.indexOf(selected);
                selected = list.get((i + 1) % list.size());
            }
            return true;
        }
        if (selected != null) {
            int dx = key == InputConstants.KEY_LEFT ? -1 : key == InputConstants.KEY_RIGHT ? 1 : 0;
            int dy = key == InputConstants.KEY_UP ? -1 : key == InputConstants.KEY_DOWN ? 1 : 0;
            if (dx != 0 || dy != 0) {
                selected.setHudPos(selected.getHudX() + dx, selected.getHudY() + dy);
                return true;
            }
            if (key == InputConstants.KEY_EQUALS || key == InputConstants.KEY_ADD) {
                scaleSelected(10);
                return true;
            }
            if (key == InputConstants.KEY_MINUS) {
                scaleSelected(-10);
                return true;
            }
            if (key == InputConstants.KEY_DELETE || key == InputConstants.KEY_BACKSPACE) {
                resetSelected();
                return true;
            }
        }
        return superKeyPressed(key, scanCode, modifiers);
    }

    // ── Actions ──────────────────────────────────────────────────────────

    private void scaleSelected(int delta) {
        if (selected == null) return;
        int x = selected.getHudX();
        int y = selected.getHudY();
        selected.style().scale.set(selected.style().scale.value + delta);
        // Grow in place, then keep it on screen at its new size.
        selected.setHudPos(x, y);
    }

    private void resetSelected() {
        if (selected == null) return;
        selected.style().scale.set(100);
        selected.resetPlacement();
    }

    private void resetAll() {
        for (AbstractHudModule h : hudModules()) {
            h.style().scale.set(100);
            h.resetPlacement();
        }
    }

    /** Stacks the visible elements down the left edge so none overlaps another. */
    private void arrange() {
        List<AbstractHudModule> list = shown();
        List<int[]> sizes = new ArrayList<>();
        for (AbstractHudModule h : list) sizes.add(new int[]{h.getHudW(), h.getHudH()});
        List<int[]> at = HudPlacement.stack(sizes, this.height, 4, 2);
        for (int i = 0; i < list.size(); i++) list.get(i).setHudPos(at.get(i)[0], at.get(i)[1]);
    }

    private void done() {
        for (AbstractHudModule h : hudModules()) HudLayout.set(h.getName(), h.getPlacement());
        HudLayout.save();
        BreezeConfig.save();
        dev.breeze.compat.ActiveScreen.set(this.minecraft, parent);
    }

    private void cancel() {
        for (AbstractHudModule h : hudModules()) {
            h.setPlacement(startPlacement.get(h.getName()));
            Integer s = startScale.get(h.getName());
            if (s != null) h.style().scale.set(s);
            Boolean on = startEnabled.get(h.getName());
            if (on != null && on != h.isEnabled()) h.setEnabled(on);
        }
        dev.breeze.compat.ActiveScreen.set(this.minecraft, parent);
    }

    @Override
    public void onClose() {
        done();
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x - 1 && mx <= x + w + 1 && my >= y - 1 && my <= y + h + 1;
    }

    private static List<HudPlacement.Box> othersThan(AbstractHudModule self) {
        List<HudPlacement.Box> out = new ArrayList<>();
        for (AbstractHudModule h : shown()) {
            if (h != self) out.add(new HudPlacement.Box(h.getHudX(), h.getHudY(), h.getHudW(), h.getHudH()));
        }
        return out;
    }

    private static boolean overlapsAnother(AbstractHudModule a) {
        for (AbstractHudModule b : shown()) {
            if (b == a) continue;
            if (a.getHudX() < b.getHudX() + b.getHudW() && b.getHudX() < a.getHudX() + a.getHudW()
                    && a.getHudY() < b.getHudY() + b.getHudH() && b.getHudY() < a.getHudY() + a.getHudH()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
