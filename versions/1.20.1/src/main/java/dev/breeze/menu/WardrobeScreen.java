package dev.breeze.menu;

import dev.breeze.compat.BreezeScreen;
import dev.breeze.compat.Screens;
import dev.breeze.cosmetics.CapeTextures;
import dev.breeze.cosmetics.CosmeticState;
import dev.breeze.cosmetics.CosmeticActions;
import dev.breeze.ui.Glass;
import dev.breeze.ui.Palette;
import dev.breeze.ui.Rect;
import dev.breeze.ui.Spacing;
import dev.breeze.ui.Theme;
import dev.breeze.ui.UiRender;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * The Wardrobe: one place to manage every cosmetic.
 *
 * Replaces a workflow that required opening the module list, disabling the cape
 * module, enabling it again, and manually fetching. That existed because the
 * mod had no way to learn about a change other than being restarted; with
 * {@link CosmeticState} polling a single authoritative endpoint, equipping
 * something here is reflected in game within a few seconds without any of that.
 *
 * The layout is deliberately built on {@link Rect} and {@link Spacing} rather
 * than hand-computed offsets, which is what caused the overlapping panels and
 * off-panel cards elsewhere in this client.
 *
 * Designed to grow: the right column is a tab strip, so hats, shoulder pets and
 * accessories become another tab and another list rather than another screen.
 */
public class WardrobeScreen extends BreezeScreen {

    private static final String[] TABS = { "Capes", "Tags", "3D" };

    private final Screen parent;
    private String activeTab = "Capes";
    private double scroll;
    private String busyId;

    private Rect panel = new Rect(0, 0, 0, 0);
    private Rect preview = new Rect(0, 0, 0, 0);
    private Rect content = new Rect(0, 0, 0, 0);
    private Rect tabStrip = new Rect(0, 0, 0, 0);

    public WardrobeScreen(Screen parent) {
        super(Component.literal("Wardrobe"));
        this.parent = parent;
    }

    /** Opens on one tab ("Capes", "Tags" or "3D"). */
    public WardrobeScreen(Screen parent, String tab) {
        this(parent);
        for (String t : TABS) if (t.equals(tab)) activeTab = t;
    }

    @Override
    protected void init() {
        // Sliced from the whole screen, so a region can never be handed out
        // twice and the leftover space is always explicit.
        panel = Rect.centered(this.width, this.height, 620, 400);
        Rect body = panel.minusTop(Spacing.HEADER_H).inset(Spacing.PANEL, 0);
        preview = body.left(190);
        Rect right = body.minusLeft(190 + Spacing.MD);
        tabStrip = right.top(Spacing.TAB_H + Spacing.SM);
        content = right.minusTop(Spacing.TAB_H + Spacing.SM).minusBottom(Spacing.PANEL);
        // A tab switch must not keep the previous tab's scroll offset, or a
        // short list opens already scrolled past its own content.
        scroll = 0;
    }

    private CosmeticState.Entry state() {
        return CosmeticState.self();
    }

    /** The 3D cosmetics this account wears, for when the game cannot equip them. */
    private static java.util.List<dev.breeze.cosmetics.WornCosmetics.Worn> worn() {
        return dev.breeze.cosmetics.WornCosmetics.account(0);
    }

    /** Whether the API takes equip changes from the game; asks again when the answer is old. */
    private static boolean canEquip3d() {
        dev.breeze.cosmetics.OwnedModels.refresh(false);
        return dev.breeze.cosmetics.OwnedModels.status() == dev.breeze.cosmetics.OwnedModels.Status.READY;
    }

    private static java.util.List<dev.breeze.cosmetics.OwnedCosmetics.Item> owned3d() {
        return dev.breeze.cosmetics.OwnedModels.items();
    }

    // ── rows ────────────────────────────────────────────────────────────────

    private int rowCount() {
        CosmeticState.Entry s = state();
        // Capes carries a leading "None" row so unequipping is one click and
        // does not need a separate button.
        // 3D ends with a note row.
        if (activeTab.equals("3D")) return (canEquip3d() ? owned3d().size() : worn().size()) + 1;
        return activeTab.equals("Capes") ? s.ownedCapes.size() + 1 : s.availableTags.size() + 1;
    }

    private int rowY(int i) {
        return content.y + i * (Spacing.ROW_H + Spacing.XS) - (int) scroll;
    }

    private int maxScroll() {
        int contentH = rowCount() * (Spacing.ROW_H + Spacing.XS);
        return Math.max(0, contentH - content.h);
    }

    private boolean rowVisible(int i) {
        int y = rowY(i);
        return y + Spacing.ROW_H > content.y && y < content.bottom();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Glass.scrim(g, this.width, this.height);
        Glass.panel(g, panel);

        g.drawString(this.font, Component.literal("Wardrobe"),
                panel.x + Spacing.MD, panel.y + 12, Palette.TEXT_PRIMARY, true);

        CosmeticState.Entry s = state();
        String sub = s.loaded
                ? (s.cape != null ? s.cape.name : "No cape equipped")
                : "Loading your cosmetics...";
        UiRender.textClipped(g, this.font, sub, panel.x + Spacing.MD + 70, panel.y + 13,
                panel.w - 160, Palette.TEXT_FAINT);

        renderPreview(g, mouseX, mouseY, s);
        renderTabs(g, mouseX, mouseY);
        renderRows(g, mouseX, mouseY, s);

        super.render(g, mouseX, mouseY, partialTick);
    }

    /**
     * The live player preview, wearing whatever is currently equipped.
     *
     * The cape drawn here is the same texture the world renderer uses, taken
     * from {@link CapeTextures}, so an animated cape animates in the preview at
     * the same rate and phase as it does in game rather than being a still
     * approximation of it.
     */
    private void renderPreview(GuiGraphics g, int mouseX, int mouseY, CosmeticState.Entry s) {
        Glass.fillRounded(g, preview.x, preview.y, preview.right(), preview.bottom(), Glass.RADIUS_SM, Palette.BG);
        Glass.roundedBorder(g, preview.x, preview.y, preview.right(), preview.bottom(), Glass.RADIUS_SM, Palette.BORDER);

        if (this.minecraft != null && this.minecraft.player != null) {
            Screens.renderPlayerPreview(g,
                    preview.centerX(), preview.bottom() - 26, 62,
                    preview.centerX() - mouseX, preview.y + 90 - mouseY,
                    this.minecraft.player);
        }

        // The equipped cape's own texture, shown flat beneath the model so the
        // full design is visible: on the model only the outer face is in view.
        if (s.cape != null) {
            ResourceLocation tex = CapeTextures.current(s.cape);
            if (tex != null) {
                int w = 40, h = 64;
                int x = preview.x + Spacing.SM;
                int y = preview.y + Spacing.SM;
                dev.breeze.compat.Draw.blit(g, tex, x, y, w, h, 1f, 1f, 10, 16, 64, 32);
                Glass.roundedBorder(g, x - 1, y - 1, x + w + 1, y + h + 1, Glass.RADIUS_SM, Palette.BORDER);
                if (s.cape.animated) {
                    Glass.badge(g, this.font, "animated", x, y + h + 3, Palette.ACCENT_DIM, Palette.ACCENT);
                }
            }
        }

        // Exactly what other players see above this player's head: role icon,
        // displayed tag, then their own tag. Rendered here rather than described
        // in text so there is nothing to misread about what is equipped.
        int lineY = preview.bottom() - 22;
        String tagLabel = s.tag != null ? "[" + s.tag.name + "]" : "[Breeze]";
        int tagColor = s.tag != null ? s.tag.color : Palette.TEXT_FAINT;
        String custom = s.customTag;
        boolean hasCustom = custom != null && !custom.isEmpty();

        int iconW = UiRender.windChargeWidth(9) + Spacing.XS;
        int textW = this.font.width(tagLabel) + (hasCustom ? this.font.width(" " + custom) : 0);
        int startX = preview.centerX() - (iconW + textW) / 2;

        int roleColor = s.badge != null ? s.badge.color : tagColor;
        UiRender.windCharge(g, startX, lineY, 9, roleColor);
        g.drawString(this.font, tagLabel, startX + iconW, lineY + 1, tagColor, false);
        if (hasCustom) {
            g.drawString(this.font, " " + custom,
                    startX + iconW + this.font.width(tagLabel), lineY + 1, s.customTagColor, false);
        }

        // Where a creator changes their own tag. The mod deliberately cannot do
        // it: the write is authenticated, and the mod holds no bearer token. A
        // uuid-addressed route would let anyone set anyone's public branding.
        if (s.canCustomTag && !hasCustom) {
            UiRender.textCentered(g, this.font, "Set your tag in the launcher",
                    new Rect(preview.x, preview.bottom() - 10, preview.w, 10), Palette.TEXT_FAINT);
        }
    }

    private void renderTabs(GuiGraphics g, int mouseX, int mouseY) {
        int x = tabStrip.x;
        for (String tab : TABS) {
            int w = this.font.width(tab) + Spacing.XL;
            Rect r = new Rect(x, tabStrip.y, w, Spacing.TAB_H);
            boolean active = tab.equals(activeTab);
            boolean hover = r.contains(mouseX, mouseY);
            Glass.surface(g, r, hover, active);
            UiRender.textCentered(g, this.font, tab, r,
                    active ? Palette.TEXT_PRIMARY : Palette.TEXT_SECONDARY);
            x += w + Spacing.XS;
        }
    }

    private void renderRows(GuiGraphics g, int mouseX, int mouseY, CosmeticState.Entry s) {
        if (!s.loaded) {
            UiRender.textClipped(g, this.font, "Loading...", content.x, content.y + Spacing.SM,
                    content.w, Palette.TEXT_FAINT);
            return;
        }

        scroll = Math.max(0, Math.min(scroll, maxScroll()));

        // Clipped so a partially scrolled row cannot bleed past the panel, which
        // is the defect the cape editor shipped with.
        g.enableScissor(content.x, content.y, content.right(), content.bottom());

        int count = rowCount();
        for (int i = 0; i < count; i++) {
            if (!rowVisible(i)) continue;
            Rect row = new Rect(content.x, rowY(i), content.w, Spacing.ROW_H);
            boolean hover = row.contains(mouseX, mouseY) && mouseY >= content.y && mouseY < content.bottom();

            if (activeTab.equals("Capes")) renderCapeRow(g, s, i, row, hover);
            else if (activeTab.equals("3D")) render3dRow(g, i, row, hover);
            else renderTagRow(g, s, i, row, hover);
        }

        g.disableScissor();

        if (count == 0) {
            UiRender.textClipped(g, this.font, "Nothing here yet.", content.x, content.y + Spacing.SM,
                    content.w, Palette.TEXT_FAINT);
        }

        int max = maxScroll();
        if (max > 0) {
            int trackH = content.h;
            int thumbH = Math.max(16, (int) ((long) trackH * trackH / (trackH + max)));
            int thumbY = content.y + (int) ((trackH - thumbH) * (scroll / max));
            g.fill(content.right() - 3, content.y, content.right() - 1, content.bottom(), Palette.BORDER);
            g.fill(content.right() - 3, thumbY, content.right() - 1, thumbY + thumbH, Palette.BORDER_HOVER);
        }
    }

    private void renderCapeRow(GuiGraphics g, CosmeticState.Entry s, int i, Rect row, boolean hover) {
        boolean isNone = i == 0;
        CosmeticState.CapeInfo cape = isNone ? null : s.ownedCapes.get(i - 1);
        boolean equipped = isNone
                ? s.cape == null
                : (s.cape != null && cape != null && cape.id.equals(s.cape.id));

        Glass.surface(g, row, hover, equipped);
        if (equipped) UiRender.accentBar(g, row.x, row.y + 3, 2, row.h - 6);

        String label = isNone ? "None" : cape.name;
        if (!isNone && cape.animated) label += "  (animated)";
        UiRender.textClipped(g, this.font, label, row.x + Spacing.SM, row.y + 6,
                row.w - Spacing.XL * 2, equipped ? Palette.TEXT_PRIMARY : Palette.TEXT_SECONDARY);

        if (busyId != null && !isNone && busyId.equals(cape.id)) {
            UiRender.textClipped(g, this.font, "...", row.right() - 20, row.y + 6, 16, Palette.ACCENT);
        }
    }

    private void render3dRow(GuiGraphics g, int i, Rect row, boolean hover) {
        if (canEquip3d()) {
            java.util.List<dev.breeze.cosmetics.OwnedCosmetics.Item> owned = owned3d();
            if (i >= owned.size()) {
                note(g, row, owned.isEmpty()
                        ? "No 3D cosmetics on this account yet."
                        : "Click to equip or remove.");
                return;
            }
            dev.breeze.cosmetics.OwnedCosmetics.Item o = owned.get(i);
            dev.breeze.devtest.Targets.put("wardrobe-3d-" + o.id, row.x + row.w / 2, row.y + row.h / 2);
            Glass.surface(g, row, hover, o.equipped);
            if (o.equipped) UiRender.accentBar(g, row.x, row.y + 3, 2, row.h - 6);
            String slot = dev.breeze.cosmetics.OwnedCosmetics.slotLabel(o.slot) + (o.equipped ? ", wearing" : "");
            int slotW = this.font.width(slot) + Spacing.SM;
            UiRender.textClipped(g, this.font, o.name, row.x + Spacing.SM, row.y + 6,
                    row.w - Spacing.SM * 2 - slotW, o.equipped ? Palette.TEXT_PRIMARY : Palette.TEXT_SECONDARY);
            boolean busy = busyId != null && busyId.equals("model-" + o.id);
            String right = busy ? "..." : slot;
            g.drawString(this.font, right, row.right() - Spacing.SM - this.font.width(right), row.y + 6,
                    busy ? Palette.ACCENT : Palette.TEXT_FAINT, false);
            return;
        }
        java.util.List<dev.breeze.cosmetics.WornCosmetics.Worn> worn = worn();
        if (i >= worn.size()) {
            note(g, row, worn.isEmpty()
                    ? "Not wearing any. Equip them in the Breeze launcher."
                    : "Change these in the Breeze launcher.");
            return;
        }
        dev.breeze.cosmetics.WornCosmetics.Worn w = worn.get(i);
        Glass.surface(g, row, false, true);
        UiRender.accentBar(g, row.x, row.y + 3, 2, row.h - 6);
        String label = w.name == null || w.name.isBlank() ? w.id : w.name;
        String slot = dev.breeze.cosmetics.OwnedCosmetics.slotLabel(w.slot);
        int slotW = slot.isEmpty() ? 0 : this.font.width(slot) + Spacing.SM;
        UiRender.textClipped(g, this.font, label, row.x + Spacing.SM, row.y + 6,
                row.w - Spacing.SM * 2 - slotW, Palette.TEXT_PRIMARY);
        if (!slot.isEmpty()) {
            g.drawString(this.font, slot, row.right() - Spacing.SM - this.font.width(slot), row.y + 6,
                    Palette.TEXT_FAINT, false);
        }
    }

    private void note(GuiGraphics g, Rect row, String text) {
        UiRender.textClipped(g, this.font, text, row.x + Spacing.SM, row.y + 6, row.w - Spacing.SM * 2, Palette.TEXT_FAINT);
    }

    private void renderTagRow(GuiGraphics g, CosmeticState.Entry s, int i, Rect row, boolean hover) {
        boolean isAuto = i == 0;
        CosmeticState.TagInfo tag = isAuto ? null : s.availableTags.get(i - 1);
        boolean equipped = isAuto
                ? s.tag == null
                : (s.tag != null && tag != null && tag.id.equals(s.tag.id));

        Glass.surface(g, row, hover, equipped);
        if (equipped) UiRender.accentBar(g, row.x, row.y + 3, 2, row.h - 6);

        String label = isAuto ? "Automatic (highest)" : "[" + tag.name + "]";
        int color = isAuto ? Palette.TEXT_SECONDARY : tag.color;
        UiRender.textClipped(g, this.font, label, row.x + Spacing.SM, row.y + 6,
                row.w - Spacing.XL * 2, color);

        // A role tag is marked because it cannot be given up: it follows the
        // account's role, so it reappearing is correct rather than a bug.
        if (!isAuto && "role".equals(tag.source)) {
            Glass.badge(g, this.font, "role", row.right() - 40, row.y + 4, Palette.SURFACE_ACTIVE, Palette.TEXT_FAINT);
        }
    }

    // ── input ───────────────────────────────────────────────────────────────

    @Override
    protected boolean onMouseClicked(double mx, double my, int button) {
        if (button == 0) {
            int x = tabStrip.x;
            for (String tab : TABS) {
                int w = this.font.width(tab) + Spacing.XL;
                if (new Rect(x, tabStrip.y, w, Spacing.TAB_H).contains(mx, my)) {
                    activeTab = tab;
                    scroll = 0;
                    return true;
                }
                x += w + Spacing.XS;
            }

            if (my >= content.y && my < content.bottom() && mx >= content.x && mx < content.right()) {
                CosmeticState.Entry s = state();
                for (int i = 0; i < rowCount(); i++) {
                    if (!rowVisible(i)) continue;
                    if (!new Rect(content.x, rowY(i), content.w, Spacing.ROW_H).contains(mx, my)) continue;
                    if (activeTab.equals("Capes")) clickCape(s, i);
                    else if (activeTab.equals("Tags")) clickTag(s, i);
                    else click3d(i);
                    return true;
                }
            }
        }
        return superMouseClicked(mx, my, button);
    }

    private void clickCape(CosmeticState.Entry s, int i) {
        if (busyId != null) return;
        if (i == 0) {
            busyId = "none";
            CosmeticActions.equipCape(null, ok -> busyId = null);
            return;
        }
        CosmeticState.CapeInfo cape = s.ownedCapes.get(i - 1);
        busyId = cape.id;
        CosmeticActions.equipCape(cape.id, ok -> busyId = null);
    }

    private void click3d(int i) {
        if (busyId != null || !canEquip3d()) return;
        java.util.List<dev.breeze.cosmetics.OwnedCosmetics.Item> owned = owned3d();
        if (i >= owned.size()) return;
        dev.breeze.cosmetics.OwnedCosmetics.Item o = owned.get(i);
        busyId = "model-" + o.id;
        if (o.equipped) dev.breeze.cosmetics.OwnedModels.unequip(o.slot, ok -> busyId = null);
        else dev.breeze.cosmetics.OwnedModels.equip(o.id, ok -> busyId = null);
    }

    private void clickTag(CosmeticState.Entry s, int i) {
        if (busyId != null) return;
        if (i == 0) {
            busyId = "none";
            CosmeticActions.equipTag(null, ok -> busyId = null);
            return;
        }
        CosmeticState.TagInfo tag = s.availableTags.get(i - 1);
        busyId = tag.id;
        CosmeticActions.equipTag(tag.id, ok -> busyId = null);
    }

    @Override
    protected boolean onMouseScrolled(double mx, double my, double scrollX, double scrollY) {
        return breeze$scroll(mx, my, scrollY);
    }

    private boolean breeze$scroll(double mx, double my, double delta) {
        if (my >= content.y && my < content.bottom()) {
            scroll = Math.max(0, Math.min(scroll - delta * 20, maxScroll()));
            return true;
        }
        return false;
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
