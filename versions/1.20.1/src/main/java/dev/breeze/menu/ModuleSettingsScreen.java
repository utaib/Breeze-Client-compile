package dev.breeze.menu;

import dev.breeze.compat.BreezeScreen;
import dev.breeze.Module;
import dev.breeze.config.BreezeConfig;
import dev.breeze.settings.Setting;
import dev.breeze.ui.Glass;
import dev.breeze.ui.Palette;
import dev.breeze.ui.Rect;
import dev.breeze.ui.Spacing;
import dev.breeze.ui.Theme;
import dev.breeze.ui.UiRender;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Edits one module's settings.
 *
 * Rows are built once in {@link #init} rather than recomputed per frame, so the
 * row a click lands on is by construction the row that was drawn. Computing
 * positions separately in the render and click paths is what produced controls
 * that activated a neighbour, and it recurs every time the two are allowed to
 * drift.
 *
 * Settings are grouped by their {@code group} field with a heading per group, so
 * a module carrying the fourteen shared appearance settings reads as three short
 * sections rather than one long list.
 */
public class ModuleSettingsScreen extends BreezeScreen {

    private static final int SLIDER_W = 90;

    private final Screen parent;
    private final Module module;

    /** A drawn row: either a group heading or a setting. */
    private static final class Row {
        final String heading;
        final Setting setting;
        Rect box = new Rect(0, 0, 0, 0);
        Rect control = new Rect(0, 0, 0, 0);

        Row(String heading) { this.heading = heading; this.setting = null; }
        Row(Setting setting) { this.heading = null; this.setting = setting; }

        boolean isHeading() { return heading != null; }
    }

    private final List<Row> rows = new ArrayList<>();
    private Rect panel = new Rect(0, 0, 0, 0);
    private Rect content = new Rect(0, 0, 0, 0);
    private double scroll;
    /** The slider being dragged, so a drag that leaves the row keeps working. */
    private Setting.Int dragging;

    public ModuleSettingsScreen(Screen parent, Module module) {
        super(Component.literal(module.getName()));
        this.parent = parent;
        this.module = module;
    }

    @Override
    protected void init() {
        panel = Rect.centered(this.width, this.height, 400, 340);
        content = panel.minusTop(Spacing.HEADER_H).inset(Spacing.PANEL, Spacing.PANEL);

        rows.clear();
        String group = null;
        for (Setting s : module.getSettings()) {
            if (!s.group.equals(group)) {
                group = s.group;
                rows.add(new Row(group));
            }
            rows.add(new Row(s));
        }
        layout();
    }

    /** Assign every row its rectangle. The single source of truth for hit tests. */
    private void layout() {
        int y = content.y - (int) scroll;
        for (Row r : rows) {
            int h = r.isHeading() ? Spacing.ROW_H : Spacing.ROW_H + 2;
            r.box = new Rect(content.x, y, content.w, h);
            int cw = r.setting instanceof Setting.Int ? SLIDER_W : 60;
            r.control = new Rect(content.right() - cw, y + 3, cw, h - 8);
            y += h + Spacing.XS;
        }
    }

    private int maxScroll() {
        int total = 0;
        for (Row r : rows) total += (r.isHeading() ? Spacing.ROW_H : Spacing.ROW_H + 2) + Spacing.XS;
        return Math.max(0, total - content.h);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
        layout();

        Glass.scrim(g, this.width, this.height);
        Glass.panel(g, panel);

        g.drawString(this.font, module.getName(), panel.x + Spacing.MD, panel.y + 12, Palette.TEXT_PRIMARY, true);
        UiRender.textClipped(g, this.font, module.getCategory().displayName(),
                panel.x + Spacing.MD + this.font.width(module.getName()) + Spacing.SM, panel.y + 13,
                panel.w - 160, Palette.TEXT_FAINT);

        g.enableScissor(content.x, content.y, content.right(), content.bottom());
        for (Row r : rows) {
            if (r.box.bottom() < content.y || r.box.y > content.bottom()) continue;
            if (r.isHeading()) {
                g.drawString(this.font, r.heading, r.box.x, r.box.y + 4, Palette.TEXT_FAINT, false);
                UiRender.divider(g, r.box.x, r.box.y + 15, r.box.w);
            } else {
                drawSetting(g, r, mouseX, mouseY);
            }
        }
        g.disableScissor();

        super.render(g, mouseX, mouseY, partialTick);
    }

    private void drawSetting(GuiGraphics g, Row r, int mouseX, int mouseY) {
        Setting s = r.setting;
        boolean hover = r.box.contains(mouseX, mouseY);
        if (hover) {
            Glass.fillRounded(g, r.box.x - 2, r.box.y, r.box.right() + 2, r.box.bottom(),
                    Glass.RADIUS_SM, Palette.SURFACE_HOVER);
        }
        UiRender.textClipped(g, this.font, s.label, r.box.x + Spacing.XS, r.box.y + 5,
                r.box.w - r.control.w - Spacing.MD, Palette.TEXT_SECONDARY);

        Rect c = r.control;
        if (s instanceof Setting.Bool) {
            Glass.toggle(g, c.right() - 26, c.y + 1, 26, 12, ((Setting.Bool) s).value);
        } else if (s instanceof Setting.Int) {
            Setting.Int i = (Setting.Int) s;
            int trackY = c.y + c.h / 2 - 1;
            Glass.fillRounded(g, c.x, trackY, c.right(), trackY + 3, 1, Palette.SURFACE_ACTIVE);
            int filled = (int) (c.w * i.fraction());
            if (filled > 0) Glass.fillRounded(g, c.x, trackY, c.x + filled, trackY + 3, 1, Theme.primary());
            int kx = c.x + filled;
            Glass.fillRounded(g, kx - 3, trackY - 3, kx + 3, trackY + 6, 3, Palette.TEXT_PRIMARY);
            // Above the track rather than beside it: at 90px the value would
            // otherwise push the slider narrow enough to be hard to aim at.
            UiRender.textClipped(g, this.font, i.display(), c.x, c.y - 6, c.w, Palette.TEXT_FAINT);
        } else if (s instanceof Setting.Color) {
            Setting.Color col = (Setting.Color) s;
            // Checker under the swatch, so a low alpha reads as transparent
            // rather than as a darker colour.
            for (int px = 0; px < c.w; px += 4) {
                for (int py = 0; py < c.h; py += 4) {
                    boolean odd = ((px / 4) + (py / 4)) % 2 == 1;
                    g.fill(c.x + px, c.y + py, Math.min(c.x + px + 4, c.right()),
                            Math.min(c.y + py + 4, c.bottom()), odd ? 0xFF3A3A3A : 0xFF565656);
                }
            }
            Glass.fillRounded(g, c.x, c.y, c.right(), c.bottom(), 3, col.argb);
            Glass.roundedBorder(g, c.x, c.y, c.right(), c.bottom(), 3, Palette.BORDER_HOVER);
        } else if (s instanceof Setting.Mode) {
            Glass.surface(g, c, r.box.contains(mouseX, mouseY), false);
            UiRender.textCentered(g, this.font, ((Setting.Mode) s).value(), c, Palette.TEXT_PRIMARY);
        }
    }

    @Override
    protected boolean onMouseClicked(double mx, double my, int button) {
        for (Row r : rows) {
            if (r.isHeading() || !r.box.contains(mx, my)) continue;
            Setting s = r.setting;

            if (s instanceof Setting.Bool) {
                ((Setting.Bool) s).toggle();
            } else if (s instanceof Setting.Int) {
                dragging = (Setting.Int) s;
                dragTo(mx);
            } else if (s instanceof Setting.Mode) {
                // Right-click steps backward, so a long option list does not
                // have to be cycled all the way round to reach the previous one.
                ((Setting.Mode) s).cycle(button == 1 ? -1 : 1);
            } else if (s instanceof Setting.Color) {
                Setting.Color col = (Setting.Color) s;
                if (button == 1) {
                    // Right-click steps alpha, which is how transparency is set
                    // without a second control taking up a row.
                    int a = col.alpha();
                    col.setAlpha(a <= 0 ? 255 : Math.max(0, a - 32));
                } else {
                    col.setRgb(nextSwatch(col.rgb()));
                }
            }
            save();
            return true;
        }
        return superMouseClicked(mx, my, button);
    }

    /**
     * Colour presets, stepped through by clicking a swatch.
     *
     * A full picker would be the better control, but it needs hue and value
     * areas and a drag model, and this screen is reachable mid-game where a
     * short list of good colours is faster than aiming inside a gradient.
     */
    private static final int[] SWATCHES = {
            0xFFFFFF, 0x78B2FF, 0x55FFFF, 0x55FF88, 0xFFD700,
            0xFF8844, 0xFF5566, 0xC77DFF, 0xA6ADBA, 0x000000,
    };

    private static int nextSwatch(int rgb) {
        for (int i = 0; i < SWATCHES.length; i++) {
            if (SWATCHES[i] == rgb) return SWATCHES[(i + 1) % SWATCHES.length];
        }
        return SWATCHES[0];
    }

    private void dragTo(double mx) {
        if (dragging == null) return;
        for (Row r : rows) {
            if (r.setting != dragging) continue;
            double f = (mx - r.control.x) / (double) Math.max(1, r.control.w);
            dragging.setFraction(Math.max(0, Math.min(1, f)));
            return;
        }
    }

    @Override
    protected boolean onMouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging != null) {
            dragTo(mx);
            return true;
        }
        return superMouseDragged(mx, my, button, dx, dy);
    }

    @Override
    protected boolean onMouseReleased(double mx, double my, int button) {
        if (dragging != null) {
            dragging = null;
            save();
            return true;
        }
        return superMouseReleased(mx, my, button);
    }

    @Override
    protected boolean onMouseScrolled(double mx, double my, double dx, double dy) {
        scroll -= dy * 18;
        return true;
    }

    private void save() {
        try { BreezeConfig.save(); } catch (Throwable ignored) {}
    }

    @Override
    public void onClose() {
        save();
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
