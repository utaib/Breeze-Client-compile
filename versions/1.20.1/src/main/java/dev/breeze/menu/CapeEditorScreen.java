package dev.breeze.menu;

import dev.breeze.compat.BreezeScreen;
import dev.breeze.compat.Screens;
import dev.breeze.ui.Spacing;
import dev.breeze.ui.Glass;
import dev.breeze.ui.Palette;
import dev.breeze.ui.Rect;

import dev.breeze.cape.ServerCapes;
import dev.breeze.ui.Theme;
import dev.breeze.ui.UiRender;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

public class CapeEditorScreen extends BreezeScreen {

    private final Screen parent;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    public CapeEditorScreen(Screen parent) {
        super(Component.literal("Cape Editor"));
        this.parent = parent;
        ServerCapes.refresh();
        ServerCapes.ensureSelected();
    }

    @Override
    protected void init() {
        panelW = Math.min(this.width - 40, 430);
        panelH = Math.min(this.height - 40, 250);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
    }

    private List<String> entries() {
        List<String> list = new ArrayList<>();
        list.add("None");
        list.addAll(ServerCapes.owned());
        return list;
    }

    private static final int CARD_W = 62;
    private static final int CARD_H = 88;
    private static final int CARD_PITCH_X = 72;
    private static final int CARD_PITCH_Y = 98;
    /** Left edge of the card grid: the preview column plus a gutter. */
    private static final int GRID_LEFT = 130;

    private double scroll;

    /** Width available to the grid, derived from the panel rather than assumed. */
    private int gridW() {
        return Math.max(CARD_W, panelW - GRID_LEFT - Spacing.MD);
    }

    /**
     * How many cards fit across.
     *
     * This was hardcoded to 4 while the panel width is responsive
     * (min(width - 40, 430)). Below roughly 470px of window width the third and
     * fourth columns extended past the panel's right edge, because the column
     * origins were absolute offsets and nothing checked them against panelW.
     */
    private int cols() {
        return Math.max(1, gridW() / CARD_PITCH_X);
    }

    private int gridTop() { return panelY + 34; }

    private int gridBottom() { return panelY + panelH - Spacing.MD; }

    private int cardX(int i) {
        return panelX + GRID_LEFT + (i % cols()) * CARD_PITCH_X;
    }

    private int cardY(int i) {
        return gridTop() + Spacing.XS + (i / cols()) * CARD_PITCH_Y - (int) scroll;
    }

    /** Scroll range, so the last row can always be reached but no further. */
    private int maxScroll(int count) {
        int rows = (count + cols() - 1) / cols();
        int content = rows * CARD_PITCH_Y - (CARD_PITCH_Y - CARD_H);
        return Math.max(0, content - (gridBottom() - gridTop() - Spacing.SM));
    }

    /** True when a card is at least partly inside the visible grid band. */
    private boolean cardVisible(int i) {
        int cy = cardY(i);
        return cy + CARD_H > gridTop() && cy < gridBottom();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Glass.scrim(g, this.width, this.height);
        Glass.panel(g, new Rect(panelX, panelY, panelW, panelH), Theme.panelBg(), Palette.BORDER);
        g.drawString(this.font, "Cape Editor", panelX + 14, panelY + 13, Palette.TEXT_PRIMARY, true);
        g.drawString(this.font, "Esc to close", panelX + panelW - 70, panelY + 13, Palette.TEXT_FAINT, false);

        Glass.fillRounded(g, panelX + 14, panelY + 34, panelX + 114, panelY + panelH - 14, Glass.RADIUS_SM, Palette.BG);
        Glass.roundedBorder(g, panelX + 14, panelY + 34, panelX + 114, panelY + panelH - 14, Glass.RADIUS_SM, Palette.BORDER);
        if (this.minecraft.player != null) {
            // Via the shim: 1.20.2 replaced the centre-point signature with a
            // bounding-box one, so the arguments differ by version.
            Screens.renderPlayerPreview(g, panelX + 64, panelY + panelH - 36, 55,
                    panelX + 64 - mouseX, panelY + 90 - mouseY, this.minecraft.player);
        }

        List<String> list = entries();
        String sel = ServerCapes.selected();
        scroll = Math.max(0, Math.min(scroll, maxScroll(list.size())));

        // Clip to the grid band. Without this the third row drew over the
        // panel's bottom edge and out onto the game behind it.
        g.enableScissor(panelX + GRID_LEFT, gridTop(), panelX + panelW - Spacing.SM, gridBottom());
        for (int i = 0; i < list.size(); i++) {
            if (!cardVisible(i)) continue;
            String name = list.get(i);
            int cx = cardX(i);
            int cy = cardY(i);
            boolean isNone = i == 0;
            boolean selNow = isNone ? (sel == null || sel.isEmpty()) : name.equalsIgnoreCase(sel);
            boolean hover = mouseX >= cx && mouseX <= cx + CARD_W && mouseY >= cy && mouseY <= cy + CARD_H;
            Glass.fillRounded(g, cx, cy, cx + CARD_W, cy + CARD_H, Glass.RADIUS_SM, hover ? Theme.cardHover() : Theme.cardBg());
            Glass.roundedBorder(g, cx, cy, cx + CARD_W, cy + CARD_H, Glass.RADIUS_SM, selNow ? Theme.primary() : Palette.BORDER);
            if (!isNone) {
                ResourceLocation tex = ServerCapes.textureFor(name);
                if (tex != null) {
                    dev.breeze.compat.Draw.blit(g, tex, cx + 11, cy + 8, 40, 64, 1f, 1f, 10, 16, 64, 32);
                } else {
                    g.drawString(this.font, "...", cx + 27, cy + 36, Palette.TEXT_SECONDARY, false);
                }
            } else {
                g.drawString(this.font, "X", cx + 28, cy + 36, Palette.TEXT_SECONDARY, false);
            }
            String label = this.font.plainSubstrByWidth(name, 58);
            g.drawString(this.font, label, cx + (CARD_W - this.font.width(label)) / 2, cy + 76, Palette.TEXT_PRIMARY, false);
        }
        g.disableScissor();

        // Scrollbar, only when there is something to scroll to.
        int max = maxScroll(list.size());
        if (max > 0) {
            int trackH = gridBottom() - gridTop();
            int thumbH = Math.max(16, (int) ((long) trackH * trackH / (trackH + max)));
            int thumbY = gridTop() + (int) ((trackH - thumbH) * (scroll / max));
            g.fill(panelX + panelW - 6, gridTop(), panelX + panelW - 4, gridBottom(), Palette.BORDER);
            g.fill(panelX + panelW - 6, thumbY, panelX + panelW - 4, thumbY + thumbH, Palette.BORDER_HOVER);
        }
        // Drawn last: these used to be emitted before the card loop, which then
        // painted the first row straight over them.
        if (!ServerCapes.ownedLoaded()) {
            g.drawString(this.font, "Loading capes...", panelX + GRID_LEFT, gridTop() + Spacing.SM, Palette.TEXT_SECONDARY, false);
        } else if (list.size() == 1) {
            g.drawString(this.font, "No capes granted to you yet.", panelX + GRID_LEFT, gridTop() + Spacing.SM + 20, Palette.TEXT_SECONDARY, false);
        }

        super.render(g, mouseX, mouseY, partialTick);
    }
    @Override
    protected boolean onMouseScrolled(double mx, double my, double scrollX, double scrollY) {
        return breeze$scroll(mx, my, scrollY);
    }

    private boolean breeze$scroll(double mx, double my, double delta) {
        if (mx >= panelX + GRID_LEFT && mx <= panelX + panelW && my >= gridTop() && my <= gridBottom()) {
            scroll = Math.max(0, Math.min(scroll - delta * 24, maxScroll(entries().size())));
            return true;
        }
        return false;
    }

    @Override
    protected boolean onMouseClicked(double mx, double my, int button) {
        if (button == 0) {
            List<String> list = entries();
            for (int i = 0; i < list.size(); i++) {
                // Same visibility rule the renderer uses. Without it, a card
                // scrolled out of view was still clickable where it used to be.
                if (!cardVisible(i)) continue;
                if (my < gridTop() || my > gridBottom()) break;
                int cx = cardX(i);
                int cy = cardY(i);
                if (mx >= cx && mx <= cx + CARD_W && my >= cy && my <= cy + CARD_H) {
                    ServerCapes.select(i == 0 ? "none" : list.get(i));
                    return true;
                }
            }
        }
        return superMouseClicked(mx, my, button);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
