package dev.breeze.menu;

import dev.breeze.compat.BreezeScreen;
import dev.breeze.ui.Glass;
import dev.breeze.ui.Palette;
import dev.breeze.ui.Rect;
import dev.breeze.ui.Theme;
import dev.breeze.ui.UiRender;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class ThemeSettingsScreen extends BreezeScreen {

    private interface IntRef {
        int get();
        void set(int v);
    }

    private static final class Slider {
        final String label;
        final IntRef ref;
        final int max;
        int x, y, w;

        Slider(String label, IntRef ref, int max) {
            this.label = label;
            this.ref = ref;
            this.max = max;
        }
    }

    /** Vertical pitch of one slider row. */
    /** Space kept clear on the right of each slider for its numeric value. */
    private static final int VALUE_GUTTER = 28;

    private static final int SLIDER_PITCH = 20;
    /** Height reserved for the preset swatch row, including its gap. */
    private static final int PRESET_BAND_H = 26;
    /** Height reserved for the Done button, including its gap. */
    private static final int FOOTER_BAND_H = 34;

    private static final int[][] PRESETS = {
            {Palette.ACCENT, 0xFF4F8CFF},
            {0xFF22D3EE, 0xFF2DD4BF},
            {0xFFEC4899, Palette.ACCENT},
            {0xFF22C55E, 0xFF84CC16},
            {0xFFF97316, 0xFFEF4444},
            {0xFF38BDF8, 0xFF818CF8},
    };

    private final Screen parent;
    private final List<Slider> sliders = new ArrayList<>();
    private int dragging = -1;
    private int panelX, panelY, panelW, panelH;

    public ThemeSettingsScreen(Screen parent) {
        super(Component.literal("Breeze Theme"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelW = Math.min(this.width - 80, 360);
        // Height is derived from content, not capped at an arbitrary 300.
        //
        // The sliders are laid out top-down from panelY+34 while the preset row
        // and the Done button are pinned to the bottom. With a fixed cap there
        // was nothing keeping them apart: on a short window the last sliders
        // drew straight over the presets and the button. Summing the sections
        // makes the overlap impossible rather than merely unlikely.
        //
        //   header 34 + sliders (7 x 20) + presets 26 + footer 34
        final int sliderRows = 7;
        int contentH = 34 + sliderRows * SLIDER_PITCH + PRESET_BAND_H + FOOTER_BAND_H;
        panelH = Math.min(this.height - (this.height < 320 ? 16 : 40), contentH);
        // On a 1280x720 window (240 units high) the full pitch does not fit,
        // and the last sliders ran into the presets row. The rows close up
        // instead, down to the 12 units a label and its knob need.
        int presetLabelTop = panelH - 50 - 12;
        int pitch = Math.max(12, Math.min(SLIDER_PITCH, (presetLabelTop - 4 - 34 - 8) / (sliderRows - 1)));
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        sliders.clear();
        addSlider("Primary R", () -> Theme.channel(Theme.primary, 16), v -> Theme.primary = Theme.setChannel(Theme.primary, 16, v), 255);
        addSlider("Primary G", () -> Theme.channel(Theme.primary, 8), v -> Theme.primary = Theme.setChannel(Theme.primary, 8, v), 255);
        addSlider("Primary B", () -> Theme.channel(Theme.primary, 0), v -> Theme.primary = Theme.setChannel(Theme.primary, 0, v), 255);
        addSlider("Accent R", () -> Theme.channel(Theme.secondary, 16), v -> Theme.secondary = Theme.setChannel(Theme.secondary, 16, v), 255);
        addSlider("Accent G", () -> Theme.channel(Theme.secondary, 8), v -> Theme.secondary = Theme.setChannel(Theme.secondary, 8, v), 255);
        addSlider("Accent B", () -> Theme.channel(Theme.secondary, 0), v -> Theme.secondary = Theme.setChannel(Theme.secondary, 0, v), 255);
        addSlider("Background", () -> Theme.bgAlpha, v -> Theme.bgAlpha = v, 255);

        int sx = panelX + 80;
        // Reserve room for the value text on the right rather than letting it
        // spill past the panel edge, which it did at every resolution.
        int sw = panelW - 100 - VALUE_GUTTER;
        int sy = panelY + 34;
        for (Slider s : sliders) {
            s.x = sx;
            s.y = sy;
            s.w = sw;
            sy += pitch;
        }

        // Minecraft's own settings (video, sound, controls, language, packs),
        // top right where the footer still has room at 720p. Done there comes
        // back here.
        addRenderableWidget(dev.breeze.compat.Widgets.button(Component.literal("Minecraft settings"),
                b -> dev.breeze.compat.ActiveScreen.set(this.minecraft, ReturnTo.from(this, dev.breeze.compat.Screens.options(this, this.minecraft))),
                panelX + panelW - 14 - 104, panelY + 8, 104, 16));

        int footY = panelY + panelH - 24;
        if (dev.breeze.ui.BreezeUi.webPossible()) {
            // Which Breeze menu opens next time. Only where this version has
            // the embedded browser; elsewhere there is nothing to choose. It
            // applies on the next open rather than swapping the screen out
            // from under the click.
            addRenderableWidget(dev.breeze.compat.Widgets.button(Component.literal(menuLabel()), b -> {
                Theme.uiMode = switch (Theme.uiMode) {
                    case AUTO -> Theme.UiMode.NATIVE;
                    case NATIVE -> Theme.UiMode.WEB;
                    case WEB -> Theme.UiMode.AUTO;
                };
                Theme.save();
                rebuildWidgets();
            }, panelX + 14, footY, 100, 18));
            addRenderableWidget(dev.breeze.compat.Widgets.button(Component.literal("Done"), b -> dev.breeze.compat.ActiveScreen.set(this.minecraft, parent), panelX + panelW - 114, footY, 100, 18));
        } else {
            addRenderableWidget(dev.breeze.compat.Widgets.button(Component.literal("Done"), b -> dev.breeze.compat.ActiveScreen.set(this.minecraft, parent), panelX + panelW / 2 - 50, footY, 100, 18));
        }
    }

    /** What the menu style button says: which Breeze menu opens next time. */
    private static String menuLabel() {
        return switch (Theme.uiMode) {
            case AUTO -> "Menu: Auto";
            case NATIVE -> "Menu: Classic";
            case WEB -> "Menu: Web";
        };
    }

    private void addSlider(String label, java.util.function.IntSupplier get, java.util.function.IntConsumer set, int max) {
        sliders.add(new Slider(label, new IntRef() {
            public int get() { return get.getAsInt(); }
            public void set(int v) { set.accept(v); }
        }, max));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Glass.scrim(g, this.width, this.height);
        Glass.panel(g, new Rect(panelX, panelY, panelW, panelH), Theme.panelBg(), Palette.BORDER);
        g.drawString(this.font, Component.literal("Theme"), panelX + 14, panelY + 12, Palette.TEXT_PRIMARY, true);

        for (Slider s : sliders) {
            g.drawString(this.font, s.label, panelX + 14, s.y - 3, Palette.TEXT_SECONDARY, false);
            Glass.fillRounded(g, s.x, s.y, s.x + s.w, s.y + 3, 1, Palette.SURFACE_ACTIVE);
            int filled = (int) ((float) s.ref.get() / s.max * s.w);
            UiRender.accentBar(g, s.x, s.y, filled, 2);
            int kx = s.x + filled;
            Glass.fillRounded(g, kx - 4, s.y - 4, kx + 4, s.y + 6, 4, Palette.TEXT_PRIMARY);
            g.drawString(this.font, String.valueOf(s.ref.get()), s.x + s.w + 6, s.y - 3, Palette.TEXT_FAINT, false);
        }

        int swy = panelY + panelH - 50;
        g.drawString(this.font, Component.literal("Presets"), panelX + 14, swy - 12, Palette.TEXT_SECONDARY, false);
        for (int i = 0; i < PRESETS.length; i++) {
            int sx = panelX + 14 + i * 26;
            UiRender.hGradient(g, sx, swy, 20, 14, PRESETS[i][0], PRESETS[i][1]);
            Glass.roundedBorder(g, sx, swy, sx + 20, swy + 14, 3,
                    inside(mouseX, mouseY, sx, swy, 20, 14) ? Palette.TEXT_PRIMARY : Palette.BORDER);
        }

        int px = panelX + panelW - 70;
        g.drawString(this.font, Component.literal("Preview"), px, swy - 12, Palette.TEXT_SECONDARY, false);
        Glass.fillRounded(g, px, swy, px + 56, swy + 14, Glass.RADIUS_SM, Theme.cardBg());
        Glass.toggle(g, px + 28, swy + 1, 24, 12, true);

        super.render(g, mouseX, mouseY, partialTick);
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    @Override
    protected boolean onMouseClicked(double mx, double my, int button) {
        if (button == 0) {
            for (int i = 0; i < sliders.size(); i++) {
                Slider s = sliders.get(i);
                if (inside(mx, my, s.x - 2, s.y - 4, s.w + 4, 10)) {
                    dragging = i;
                    applySlider(s, mx);
                    return true;
                }
            }
            int swy = panelY + panelH - 50;
            for (int i = 0; i < PRESETS.length; i++) {
                int sx = panelX + 14 + i * 26;
                if (inside(mx, my, sx, swy, 20, 14)) {
                    Theme.primary = PRESETS[i][0];
                    Theme.secondary = PRESETS[i][1];
                    Theme.save();
                    return true;
                }
            }
        }
        return superMouseClicked(mx, my, button);
    }

    @Override
    protected boolean onMouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging >= 0 && dragging < sliders.size()) {
            applySlider(sliders.get(dragging), mx);
            return true;
        }
        return superMouseDragged(mx, my, button, dx, dy);
    }

    @Override
    protected boolean onMouseReleased(double mx, double my, int button) {
        if (dragging >= 0) {
            Theme.save();
            dragging = -1;
            return true;
        }
        return superMouseReleased(mx, my, button);
    }

    private void applySlider(Slider s, double mx) {
        float t = (float) ((mx - s.x) / s.w);
        s.ref.set(Math.round(Math.max(0, Math.min(1, t)) * s.max));
    }

    @Override
    public void onClose() {
        dev.breeze.compat.ActiveScreen.set(this.minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
