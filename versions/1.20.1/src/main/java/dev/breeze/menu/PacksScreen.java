package dev.breeze.menu;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.breeze.compat.BreezeScreen;
import dev.breeze.compat.Widgets;
import dev.breeze.packs.PackMenu;
import dev.breeze.ui.Glass;
import dev.breeze.ui.Palette;
import dev.breeze.ui.Rect;
import dev.breeze.ui.Spacing;
import dev.breeze.ui.UiRender;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Resource packs in the native menus: Minecraft's own pack list, the packs
 * that are on first (top first, the top one wins), then the rest. A row can
 * be switched on or off and, when on, moved up or down; nothing changes
 * until Apply (or Done), which goes through Minecraft's own options
 * (PackMenu), so resources reload once.
 */
public class PacksScreen extends BreezeScreen {

    private static final int ROW_H = 28;
    private static final int ARROW = 14;

    private record Row(String id, String title, String description, boolean compatible, String compatibility,
                       boolean required, boolean fixed) {}

    private final Screen parent;
    private Rect panel = new Rect(0, 0, 0, 0);
    private Rect content = new Rect(0, 0, 0, 0);
    private final List<Row> all = new ArrayList<>();
    /** The packs on, top first, as they would be applied. */
    private final List<String> on = new ArrayList<>();
    private List<String> applied = new ArrayList<>();
    private double scroll;
    private String notice;
    private boolean loaded;

    public PacksScreen(Screen parent) {
        super(Component.literal("Resource packs"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panel = Rect.centered(this.width, this.height, 440, Math.min(340, this.height - 20));
        content = new Rect(panel.x + Spacing.MD, panel.y + 52, panel.w - Spacing.MD * 2, panel.h - 52 - 40);
        if (!loaded) load();
        int y = panel.bottom() - 30;
        addRenderableWidget(Widgets.button(Component.literal("Apply"), b -> apply(), panel.x + Spacing.MD, y, 80, 20));
        addRenderableWidget(Widgets.button(Component.literal("Open the folder"), b -> {
            try {
                if (this.minecraft != null) PackMenu.openFolder(this.minecraft);
            } catch (Throwable t) {
                notice = "The resource packs folder could not be opened.";
            }
        }, panel.x + Spacing.MD + 86, y, 110, 20));
        addRenderableWidget(Widgets.button(Component.literal("Done"), b -> onClose(), panel.right() - Spacing.MD - 70, y, 70, 20));
    }

    private void load() {
        if (this.minecraft == null) return;
        all.clear();
        on.clear();
        try {
            JsonObject o = PackMenu.json(this.minecraft, false);
            for (JsonElement el : o.getAsJsonArray("packs")) {
                JsonObject p = el.getAsJsonObject();
                all.add(new Row(p.get("id").getAsString(), p.get("title").getAsString(), p.get("description").getAsString(),
                        p.get("compatible").getAsBoolean(), p.get("compatibility").getAsString(),
                        p.get("required").getAsBoolean(), p.get("fixed").getAsBoolean()));
            }
            for (JsonElement el : o.getAsJsonArray("selected")) on.add(el.getAsString());
            applied = new ArrayList<>(on);
            loaded = true;
        } catch (Throwable t) {
            notice = "Minecraft's pack list could not be read.";
        }
    }

    private void apply() {
        if (this.minecraft == null || on.equals(applied)) return;
        try {
            PackMenu.apply(this.minecraft, new ArrayList<>(on));
            loaded = false;
            load();
            notice = "Applied. Minecraft reloads its resources.";
        } catch (Throwable t) {
            notice = "The packs could not be applied.";
        }
    }

    /** On packs first, top first, then the rest in Minecraft's order. */
    private List<Row> shown() {
        List<Row> out = new ArrayList<>();
        for (String id : on) {
            for (Row r : all) if (r.id().equals(id)) out.add(r);
        }
        for (Row r : all) if (!on.contains(r.id())) out.add(r);
        return out;
    }

    private Rect rowBox(int i) {
        return new Rect(content.x, content.y - (int) scroll + i * (ROW_H + Spacing.XS), content.w, ROW_H);
    }

    private Rect toggleCell(Rect row) {
        return new Rect(row.x + Spacing.XS, row.y + (row.h - 12) / 2, 26, 12);
    }

    private Rect upCell(Rect row) {
        return new Rect(row.right() - ARROW * 2 - Spacing.XS, row.y + (row.h - ARROW) / 2, ARROW, ARROW);
    }

    private Rect downCell(Rect row) {
        return new Rect(row.right() - ARROW, row.y + (row.h - ARROW) / 2, ARROW, ARROW);
    }

    private static String compatibilityNote(Row r) {
        if (r.compatible()) return null;
        return "too_old".equals(r.compatibility()) ? "Made for an older Minecraft" : "Made for a newer Minecraft";
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        List<Row> rows = shown();
        int total = rows.size() * (ROW_H + Spacing.XS);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, total - content.h)));
        Glass.scrim(g, this.width, this.height);
        Glass.panel(g, panel);
        g.drawString(this.font, "Resource packs", panel.x + Spacing.MD, panel.y + 12, Palette.TEXT_PRIMARY, true);
        UiRender.textClipped(g, this.font, "Packs on are listed first; the top one wins where packs overlap.",
                panel.x + Spacing.MD, panel.y + 28, panel.w - Spacing.MD * 2, Palette.TEXT_FAINT);

        g.enableScissor(content.x, content.y, content.right(), content.bottom());
        if (rows.isEmpty()) g.drawString(this.font, "No resource packs found.", content.x, content.y + 4, Palette.TEXT_SECONDARY, false);
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            Rect row = rowBox(i);
            if (row.bottom() < content.y || row.y > content.bottom()) continue;
            boolean isOn = on.contains(r.id());
            if (row.contains(mouseX, mouseY)) {
                Glass.fillRounded(g, row.x - 2, row.y, row.right() + 2, row.bottom(), Glass.RADIUS_SM, Palette.SURFACE_HOVER);
            }
            Rect t = toggleCell(row);
            if (!r.required()) Glass.toggle(g, t.x, t.y, t.w, t.h, isOn);
            int textX = t.right() + Spacing.SM;
            int textW = upCell(row).x - textX - Spacing.SM;
            UiRender.textClipped(g, this.font, r.title(), textX, row.y + 4, textW, isOn ? Palette.TEXT_PRIMARY : Palette.TEXT_SECONDARY);
            String note = compatibilityNote(r);
            String second = r.required() ? "Always on" : note != null ? note : r.description().replace('\n', ' ');
            UiRender.textClipped(g, this.font, second, textX, row.y + 16, textW,
                    note != null && !r.required() ? 0xFFF5C542 : Palette.TEXT_FAINT);
            if (isOn && !r.fixed()) {
                Rect up = upCell(row), down = downCell(row);
                UiRender.textCentered(g, this.font, "▲", up, up.contains(mouseX, mouseY) ? Palette.TEXT_PRIMARY : Palette.TEXT_FAINT);
                UiRender.textCentered(g, this.font, "▼", down, down.contains(mouseX, mouseY) ? Palette.TEXT_PRIMARY : Palette.TEXT_FAINT);
            }
            dev.breeze.devtest.Targets.put("pack-" + r.id(), t.x + t.w / 2, t.y + t.h / 2);
        }
        g.disableScissor();
        String status = notice != null ? notice : !on.equals(applied) ? "Not applied yet: Apply or Done reloads resources." : null;
        if (status != null) {
            UiRender.textClipped(g, this.font, status, panel.x + Spacing.MD + 202, panel.bottom() - 24,
                    panel.w - Spacing.MD * 2 - 202 - 76, notice != null ? Palette.TEXT_SECONDARY : 0xFFF5C542);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    /** Moves an on pack among the movable on packs; fixed ones stay where Minecraft keeps them. */
    private void move(String id, int by) {
        int i = on.indexOf(id);
        int j = i + by;
        if (i < 0 || j < 0 || j >= on.size()) return;
        for (Row r : all) if (r.id().equals(on.get(j)) && r.fixed()) return;
        on.set(i, on.get(j));
        on.set(j, id);
    }

    @Override
    protected boolean onMouseClicked(double mx, double my, int button) {
        if (my >= content.y && my <= content.bottom()) {
            List<Row> rows = shown();
            for (int i = 0; i < rows.size(); i++) {
                Row r = rows.get(i);
                Rect row = rowBox(i);
                if (!row.contains(mx, my)) continue;
                notice = null;
                boolean isOn = on.contains(r.id());
                if (isOn && !r.fixed() && upCell(row).contains(mx, my)) move(r.id(), -1);
                else if (isOn && !r.fixed() && downCell(row).contains(mx, my)) move(r.id(), 1);
                else if (!r.required() && toggleCell(row).contains(mx, my)) {
                    if (isOn) on.remove(r.id());
                    else on.add(0, r.id());
                }
                return true;
            }
        }
        return superMouseClicked(mx, my, button);
    }

    @Override
    protected boolean onMouseScrolled(double mx, double my, double dx, double dy) {
        scroll -= dy * 18;
        return true;
    }

    @Override
    public void onClose() {
        apply();
        if (this.minecraft != null) dev.breeze.compat.ActiveScreen.set(this.minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
