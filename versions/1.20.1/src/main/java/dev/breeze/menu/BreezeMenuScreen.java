package dev.breeze.menu;

import dev.breeze.compat.BreezeScreen;
import dev.breeze.Category;
import dev.breeze.Module;
import dev.breeze.ModuleManager;
import dev.breeze.config.BreezeConfig;
import dev.breeze.ui.Glass;
import dev.breeze.ui.Palette;
import dev.breeze.ui.Rect;
import dev.breeze.ui.Spacing;
import dev.breeze.ui.BreezeUi;
import dev.breeze.ui.Theme;
import dev.breeze.ui.UiRender;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class BreezeMenuScreen extends BreezeScreen {

    private static final int SIDEBAR_W = 104;
    private static final int PANEL_GAP = 10;
    private static final int HEADER = 36;
    /** Up to three columns, as many as keep each card this wide (a name like "Server Address" fits). */
    private static final int MAX_COLS = 3;
    private static final int MIN_CARD_W = 120;
    private static final int CARD_H = 46;
    private static final int GAP = 8;

    private static final String[] TABS = {"All", "Favorites", "HUD", "Visual", "Utility", "Chat", "Performance", "PvP"};

    private int sidebarX;
    private int sidebarY;
    private int panelH;
    private int mainX;
    private int mainY;
    private int mainW;
    private int gridX;
    private int gridY;
    private int gridW;
    private int gridBottom;
    private int cardW;
    private int cols = MAX_COLS;
    /** The search box's left and right edges (its rounded background). */
    private int searchL;
    private int searchR;

    private EditBox search;
    private String activeTab = "All";
    private double scroll;

    public BreezeMenuScreen() {
        super(Component.literal("Breeze"));
    }

    @Override
    protected void init() {
        // Minecraft's automatic GUI scale makes a 1280x720 window 426x240
        // units and 1920x1080 480x270, so those are the normal sizes, not the
        // small ones. The margins shrink there instead of the content.
        int marginX = this.width < 560 ? 8 : 40;
        int marginY = this.height < 320 ? 8 : 30;
        panelH = Math.min(this.height - 2 * marginY, 470);
        mainW = Math.min(this.width - 2 * marginX - SIDEBAR_W - PANEL_GAP, 640);
        int groupW = SIDEBAR_W + PANEL_GAP + mainW;
        int sx = (this.width - groupW) / 2;
        int sy = (this.height - panelH) / 2;
        sidebarX = sx;
        sidebarY = sy;
        mainX = sx + SIDEBAR_W + PANEL_GAP;
        mainY = sy;
        gridX = mainX + 12;
        gridY = mainY + HEADER;
        gridW = mainW - 24;
        gridBottom = mainY + panelH - 12;
        cols = Math.max(1, Math.min(MAX_COLS, (gridW + GAP) / (MIN_CARD_W + GAP)));
        cardW = (gridW - (cols - 1) * GAP) / cols;

        // The search box takes what the header can spare, between 90 and 138
        // wide, and stops short of the close button.
        int searchW = Math.max(90, Math.min(138, mainW - 150));
        searchR = mainX + mainW - 22;
        searchL = searchR - searchW - 6;
        String prev = search != null ? search.getValue() : "";
        search = new EditBox(this.font, searchL + 6, mainY + 11, searchW - 4, 16, Component.literal("Search"));
        dev.breeze.compat.Widgets.hint(search, Component.literal("Search"));
        search.setBordered(false);
        search.setValue(prev);
        search.setResponder(s -> scroll = 0);
        addRenderableWidget(search);

        // Which menu opens next time (web or classic) is chosen in Breeze's
        // settings (the gear), where it has room on every screen size.
    }

    private List<Module> filtered() {
        String q = search == null ? "" : search.getValue().toLowerCase();
        List<Module> out = new ArrayList<>();
        for (Module m : ModuleManager.getModules()) {
            if (!q.isEmpty() && !m.getName().toLowerCase().contains(q)) continue;
            if (activeTab.equals("All")) {
                out.add(m);
            } else if (activeTab.equals("Favorites")) {
                if (Theme.isFavorite(m.getName())) out.add(m);
            } else if (m.getCategory() == categoryOf(activeTab)) {
                out.add(m);
            }
        }
        return out;
    }

    private static Category categoryOf(String tab) {
        switch (tab) {
            case "HUD": return Category.HUD;
            case "Visual": return Category.VISUAL;
            case "Utility": return Category.UTILITY;
            case "Chat": return Category.CHAT;
            case "Performance": return Category.PERFORMANCE;
            case "PvP": return Category.PVP;
            default: return null;
        }
    }

    /** Logo plus the divider under it. */
    private static final int SIDEBAR_HEADER_H = 52;
    /**
     * The wardrobe, HUD editor and settings icons, side by side in one row.
     * Stacked, they took 80 units, which left room for three of the eight
     * category tabs on a 1280x720 window.
     */
    private static final int SIDEBAR_FOOTER_H = Spacing.HIT + 2 * Spacing.XS;
    private static final int ICON_GAP = 6;

    private int tabsTop() { return sidebarY + SIDEBAR_HEADER_H; }

    private int tabsBottom() { return sidebarY + panelH - SIDEBAR_FOOTER_H; }

    /**
     * Row height for the tab stack.
     *
     * The tabs used to be laid out top-down at a fixed pitch while the HUD and
     * gear icons were pinned bottom-up, with nothing reserving the space
     * between. At 1080p with GUI scale 4 the panel is short enough that the two
     * met: the last tabs drew over the icons and the click handlers overlapped,
     * so pressing one activated the other. Deriving the pitch from the space
     * that is actually left makes the collision impossible rather than unlikely.
     */
    private int tabH() {
        int available = tabsBottom() - tabsTop();
        if (available <= 0) return Spacing.ROW_H;
        return Math.max(14, Math.min(Spacing.TAB_H, available / TABS.length));
    }

    private int tabY(int i) {
        return tabsTop() + i * tabH();
    }

    /** The footer row: wardrobe (0), HUD editor (1), settings (2), centred. */
    private int iconX(int i) {
        int rowW = 3 * Spacing.HIT + 2 * ICON_GAP;
        return sidebarX + (SIDEBAR_W - rowW) / 2 + i * (Spacing.HIT + ICON_GAP);
    }

    private int iconY() { return sidebarY + panelH - SIDEBAR_FOOTER_H + Spacing.XS; }

    private int maxScroll(int count) {
        int rows = (count + cols - 1) / cols;
        int content = rows * (CARD_H + GAP) - GAP;
        int visible = gridBottom - gridY;
        return Math.max(0, content - visible);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Launcher-weight scrim. The old 0x40 wash left the game legible
        // behind the panels, which is why they read as an overlay rather than
        // as an application on top of it.
        Glass.scrim(g, this.width, this.height);

        Glass.panel(g, new Rect(sidebarX, sidebarY, SIDEBAR_W, panelH));
        UiRender.logo(g, sidebarX + SIDEBAR_W / 2 - 16, sidebarY + 12, 32, 1f);
        UiRender.divider(g, sidebarX + Spacing.MD, sidebarY + 47, SIDEBAR_W - Spacing.XL);

        int th = tabH();
        for (int i = 0; i < TABS.length; i++) {
            int ty = tabY(i);
            // Belt and braces: even with the derived pitch, never paint into the
            // footer if the panel is pathologically short.
            if (ty + th > tabsBottom()) break;
            boolean active = TABS[i].equals(activeTab);
            boolean hov = inside(mouseX, mouseY, sidebarX + Spacing.XS, ty - 3, SIDEBAR_W - Spacing.SM, th - 2);
            if (active) {
                Glass.fillRounded(g, sidebarX + Spacing.XS, ty - 3, sidebarX + SIDEBAR_W - Spacing.XS, ty + th - 5, Glass.RADIUS_SM, Palette.ACCENT_DIM);
                UiRender.accentBar(g, sidebarX + Spacing.XS, ty - 2, 2, th - 4);
            }
            int col = active ? Palette.TEXT_PRIMARY : hov ? Palette.TEXT_SECONDARY : Palette.TEXT_FAINT;
            UiRender.textClipped(g, this.font, TABS[i], sidebarX + 14, ty, SIDEBAR_W - 20, col);
        }

        boolean wardHover = inside(mouseX, mouseY, iconX(0), iconY(), Spacing.HIT, Spacing.HIT);
        UiRender.hanger(g, iconX(0), iconY(), Spacing.HIT, wardHover ? Theme.secondary() : Palette.TEXT_SECONDARY);
        boolean hudHover = inside(mouseX, mouseY, iconX(1), iconY(), Spacing.HIT, Spacing.HIT);
        UiRender.moveIcon(g, iconX(1), iconY(), Spacing.HIT, hudHover ? Theme.secondary() : Palette.TEXT_SECONDARY);
        boolean gearHover = inside(mouseX, mouseY, iconX(2), iconY(), Spacing.HIT, Spacing.HIT);
        UiRender.gear(g, iconX(2), iconY(), Spacing.HIT, gearHover ? Theme.secondary() : Palette.TEXT_SECONDARY);
        dev.breeze.devtest.Targets.put("settings-icon", iconX(2) + Spacing.HIT / 2, iconY() + Spacing.HIT / 2);

        Glass.panel(g, new Rect(mainX, mainY, mainW, panelH));
        // The 200x200 logo watermark that used to sit here is gone. It was drawn
        // between the panel fill and every piece of content, outside any scissor
        // and at a size unrelated to the panel, so on smaller panels it bled past
        // the edges and showed through the cards. That is the "logo behind the
        // menus" artifact. A watermark, if wanted later, belongs inside the
        // content scissor and sized from the panel.

        g.drawString(this.font, Component.literal("Breeze"), mainX + Spacing.MD, mainY + 13, Palette.TEXT_PRIMARY, true);
        UiRender.accentBar(g, mainX + 14, mainY + 26, 44, 2);
        // The tab name and the hint both stop short of the search box.
        int tabW = UiRender.textClipped(g, this.font, activeTab, mainX + 66, mainY + 14,
                searchL - Spacing.SM - (mainX + 66), Palette.TEXT_FAINT);
        // Right-click is not discoverable on its own, and a cog on every card
        // would cost 14px of a 46px card that already carries four controls.
        int hintX = mainX + 66 + tabW + Spacing.MD;
        if (searchL - Spacing.SM - hintX >= this.font.width("right-click to style")) {
            g.drawString(this.font, "right-click to style", hintX, mainY + 14, Palette.TEXT_FAINT, false);
        }

        Glass.fillRounded(g, searchL, mainY + 9, searchR, mainY + 27, Glass.RADIUS_SM, Palette.BG);
        Glass.roundedBorder(g, searchL, mainY + 9, searchR, mainY + 27, Glass.RADIUS_SM, Palette.BORDER);
        boolean closeHover = inside(mouseX, mouseY, mainX + mainW - 16, mainY + 13, 8, 8);
        UiRender.close(g, mainX + mainW - 16, mainY + 13, 8, closeHover ? Theme.primary() : Palette.TEXT_SECONDARY);

        List<Module> list = filtered();
        scroll = Math.max(0, Math.min(scroll, maxScroll(list.size())));

        g.enableScissor(gridX, gridY, gridX + gridW, gridBottom);
        Module hovered = null;
        for (int i = 0; i < list.size(); i++) {
            int cx = gridX + (i % cols) * (cardW + GAP);
            int cy = (int) (gridY + (i / cols) * (CARD_H + GAP) - scroll);
            if (cy + CARD_H < gridY || cy > gridBottom) continue;
            Module m = list.get(i);
            boolean over = inside(mouseX, mouseY, cx, cy, cardW, CARD_H) && mouseY >= gridY && mouseY <= gridBottom;
            if (over) hovered = m;
            drawCard(g, m, cx, cy, over);
            if (i == 0) dev.breeze.devtest.Targets.put("module-card-0", cx + cardW / 3, cy + CARD_H / 2);
        }
        g.disableScissor();

        int ms = maxScroll(list.size());
        if (ms > 0) {
            int trackH = gridBottom - gridY;
            int barH = Math.max(20, (int) ((long) trackH * trackH / (trackH + ms)));
            int barY = gridY + (int) ((trackH - barH) * (scroll / ms));
            g.fill(mainX + mainW - 5, gridY, mainX + mainW - 3, gridBottom, Palette.BORDER_HOVER);
            Glass.fillRounded(g, mainX + mainW - 5, barY, mainX + mainW - 3, barY + barH, 1, Theme.accent(0.5f));
        }

        super.render(g, mouseX, mouseY, partialTick);

        if (hovered != null && hovered.getDescription() != null && !hovered.getDescription().isEmpty()) {
            dev.breeze.compat.Draw.tooltip(g, this.font, Component.literal(hovered.getDescription()), mouseX, mouseY);
        }
    }

    private void drawCard(GuiGraphics g, Module m, int x, int y, boolean hover) {
        // A card sits ON the panel, so it climbs the surface ladder rather than
        // being a darker hole in it. Hover climbs one step further, which is
        // what makes the grid feel responsive instead of static.
        Glass.fillRounded(g, x, y, x + cardW, y + CARD_H, Glass.RADIUS_SM,
                Palette.surfaceFor(hover, m.isEnabled()));
        Glass.roundedBorder(g, x, y, x + cardW, y + CARD_H, Glass.RADIUS_SM,
                (hover || m.isEnabled()) ? Palette.BORDER_HOVER : Palette.BORDER);
        if (m.isEnabled()) UiRender.accentBar(g, x, y + 6, 2, CARD_H - 12);

        // Both lines clip. The subtitle previously did not, so long category
        // text ran under the toggle. The name stops before the favourite heart
        // in the top-right corner (it used to run under it), the subtitle
        // before the toggle beside it.
        UiRender.textClipped(g, this.font, m.getName(), x + 10, y + 9, cardW - 32, Palette.TEXT_PRIMARY);
        UiRender.textClipped(g, this.font, m.getCategory().displayName(), x + 10, y + 21, cardW - 44, Palette.TEXT_FAINT);

        Glass.toggle(g, x + cardW - 34, y + CARD_H - 18, 26, 12, m.isEnabled());
        UiRender.heart(g, x + cardW - 16, y + 8, Theme.isFavorite(m.getName()));
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    @Override
    protected boolean onMouseClicked(double mx, double my, int button) {
        // Right-click a card to configure it. A dedicated cog on every card
        // would need ~14px of a 46px-tall card that already carries a name, a
        // category, a toggle and a favourite star, and would be a small target
        // at GUI scale 1.
        if (button == 1 && mx >= gridX && mx <= gridX + gridW && my >= gridY && my <= gridBottom) {
            List<Module> list = filtered();
            for (int i = 0; i < list.size(); i++) {
                int cx = gridX + (i % cols) * (cardW + GAP);
                int cy = (int) (gridY + (i / cols) * (CARD_H + GAP) - scroll);
                if (!inside(mx, my, cx, cy, cardW, CARD_H)) continue;
                Module m = list.get(i);
                if (m.hasSettings()) dev.breeze.compat.ActiveScreen.set(this.minecraft, new ModuleSettingsScreen(this, m));
                return true;
            }
        }
        if (button == 0) {
            if (inside(mx, my, mainX + mainW - 16, mainY + 13, 10, 10)) {
                onClose();
                return true;
            }
            // Same geometry helpers the renderer uses. When these were separate
            // literals the hit areas drifted from what was drawn, so a click
            // near the bottom of the tab stack opened the HUD editor instead.
            if (inside(mx, my, iconX(0), iconY(), Spacing.HIT, Spacing.HIT)) {
                dev.breeze.compat.ActiveScreen.set(this.minecraft, new WardrobeScreen(this));
                return true;
            }
            if (inside(mx, my, iconX(1), iconY(), Spacing.HIT, Spacing.HIT)) {
                dev.breeze.compat.ActiveScreen.set(this.minecraft, new HudEditorScreen(this));
                return true;
            }
            if (inside(mx, my, iconX(2), iconY(), Spacing.HIT, Spacing.HIT)) {
                dev.breeze.compat.ActiveScreen.set(this.minecraft, new ThemeSettingsScreen(this));
                return true;
            }
            int th = tabH();
            for (int i = 0; i < TABS.length; i++) {
                int ty = tabY(i);
                if (ty + th > tabsBottom()) break;
                if (inside(mx, my, sidebarX + Spacing.XS, ty - 3, SIDEBAR_W - Spacing.SM, th - 2)) {
                    activeTab = TABS[i];
                    scroll = 0;
                    return true;
                }
            }
            if (mx >= gridX && mx <= gridX + gridW && my >= gridY && my <= gridBottom) {
                List<Module> list = filtered();
                for (int i = 0; i < list.size(); i++) {
                    int cx = gridX + (i % cols) * (cardW + GAP);
                    int cy = (int) (gridY + (i / cols) * (CARD_H + GAP) - scroll);
                    if (!inside(mx, my, cx, cy, cardW, CARD_H)) continue;
                    Module m = list.get(i);
                    if (inside(mx, my, cx + cardW - 16, cy + 8, 8, 8)) {
                        Theme.toggleFavorite(m.getName());
                    } else {
                        m.toggle();
                        BreezeConfig.save();
                    }
                    return true;
                }
            }
        }
        return superMouseClicked(mx, my, button);
    }

    @Override
    protected boolean onMouseScrolled(double mx, double my, double scrollX, double scrollY) {
        return breeze$scroll(mx, my, scrollY);
    }

    private boolean breeze$scroll(double mx, double my, double delta) {
        if (mx >= gridX && mx <= gridX + gridW && my >= gridY && my <= gridBottom) {
            scroll = Math.max(0, scroll - delta * 24);
            return true;
        }
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
