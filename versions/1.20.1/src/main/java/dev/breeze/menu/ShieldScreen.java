package dev.breeze.menu;

import dev.breeze.compat.BreezeScreen;
import dev.breeze.compat.Draw;
import dev.breeze.compat.Widgets;
import dev.breeze.config.BreezeConfig;
import dev.breeze.cosmetics.ShieldArt;
import dev.breeze.cosmetics.ShieldMenu;
import dev.breeze.cosmetics.ShieldShare;
import dev.breeze.cosmetics.ShieldTextures;
import dev.breeze.modules.CustomShield;
import dev.breeze.ui.Glass;
import dev.breeze.ui.Palette;
import dev.breeze.ui.Rect;
import dev.breeze.ui.Spacing;
import dev.breeze.ui.UiRender;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Custom Shield's picture in the native menus (from the module's settings):
 * the pictures in the breeze_shields folder to choose from, and the shield's
 * front drawn with the texture the game will use. The web menu has the same
 * on the module's page (ShieldPanel).
 */
public class ShieldScreen extends BreezeScreen {

    private static final int ROW_H = 18;
    private static final int FACE_W = 60, FACE_H = 110;

    private final Screen parent;
    private Rect panel = new Rect(0, 0, 0, 0);
    private Rect list = new Rect(0, 0, 0, 0);
    private Rect face = new Rect(0, 0, 0, 0);
    private final List<String> rows = new ArrayList<>();
    private double scroll;
    private String notice;

    public ShieldScreen(Screen parent) {
        super(Component.literal("Custom Shield"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panel = Rect.centered(this.width, this.height, 400, Math.min(300, this.height - 20));
        face = new Rect(panel.x + Spacing.MD, panel.y + 48, FACE_W, FACE_H);
        list = new Rect(face.right() + Spacing.MD, panel.y + 48, panel.right() - Spacing.MD - face.right() - Spacing.MD, panel.h - 48 - 64);
        reload();
        int y = panel.bottom() - 28;
        addRenderableWidget(Widgets.button(fitLabel(), b -> {
            CustomShield.setFit(CustomShield.fit() == ShieldArt.Fit.COVER ? CustomShield.WHOLE : CustomShield.FILL);
            save();
            b.setMessage(fitLabel());
        }, panel.x + Spacing.MD, y, 120, 20));
        addRenderableWidget(Widgets.button(Component.literal("Open the folder"), b -> {
            try {
                if (this.minecraft != null) ShieldMenu.openFolder(this.minecraft);
            } catch (Throwable t) {
                notice = "The breeze_shields folder could not be opened.";
            }
        }, panel.x + Spacing.MD + 126, y, 110, 20));
        addRenderableWidget(Widgets.button(Component.literal("Done"), b -> onClose(), panel.right() - Spacing.MD - 70, y, 70, 20));
    }

    private static Component fitLabel() {
        return Component.literal("Fit: " + (CustomShield.fit() == ShieldArt.Fit.CONTAIN ? CustomShield.WHOLE : CustomShield.FILL));
    }

    private void reload() {
        rows.clear();
        rows.add("");
        if (this.minecraft != null) rows.addAll(ShieldTextures.pictures(this.minecraft));
    }

    private void save() {
        try {
            BreezeConfig.save();
        } catch (Throwable ignored) {}
    }

    private Rect rowBox(int i) {
        return new Rect(list.x, list.y - (int) scroll + i * ROW_H, list.w, ROW_H);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int total = rows.size() * ROW_H;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, total - list.h)));
        Glass.scrim(g, this.width, this.height);
        Glass.panel(g, panel);
        g.drawString(this.font, "Custom Shield", panel.x + Spacing.MD, panel.y + 12, Palette.TEXT_PRIMARY, true);
        UiRender.textClipped(g, this.font, "Pictures from the breeze_shields folder, on the shield in your hands.",
                panel.x + Spacing.MD, panel.y + 28, panel.w - Spacing.MD * 2, Palette.TEXT_FAINT);

        String chosen = CustomShield.picture();
        drawFace(g, chosen);

        Glass.fillRounded(g, list.x, list.y, list.right(), list.bottom(), Glass.RADIUS_SM, Palette.SURFACE);
        g.enableScissor(list.x, list.y, list.right(), list.bottom());
        for (int i = 0; i < rows.size(); i++) {
            Rect r = rowBox(i);
            if (r.bottom() < list.y || r.y > list.bottom()) continue;
            String name = rows.get(i);
            boolean on = name.equals(chosen);
            if (r.contains(mouseX, mouseY)) g.fill(r.x, r.y, r.right(), r.bottom(), Palette.SURFACE_HOVER);
            int dot = on ? 0xFF78B2FF : Palette.TEXT_FAINT;
            g.fill(r.x + 6, r.y + 7, r.x + 10, r.y + 11, dot);
            UiRender.textClipped(g, this.font, name.isEmpty() ? "No picture" : name, r.x + 16, r.y + 5, r.w - 20,
                    on ? Palette.TEXT_PRIMARY : Palette.TEXT_SECONDARY);
            dev.breeze.devtest.Targets.put("shield-pick-" + (name.isEmpty() ? "none" : name), r.x + r.w / 2, r.y + r.h / 2);
        }
        g.disableScissor();
        if (rows.size() == 1) {
            UiRender.textClipped(g, this.font, "No pictures yet: put a PNG or JPEG in the folder.", list.x + 6, list.y + ROW_H + 5,
                    list.w - 12, Palette.TEXT_FAINT);
        }

        String status = statusLine(chosen);
        if (status != null) {
            UiRender.textClipped(g, this.font, status, panel.x + Spacing.MD, panel.bottom() - 44, panel.w - Spacing.MD * 2,
                    notice != null || ShieldTextures.ownProblem() != null ? 0xFFF5C542 : Palette.TEXT_SECONDARY);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    /** The shield's front with the texture the game will draw it with, or a plain panel. */
    private void drawFace(GuiGraphics g, String chosen) {
        Glass.fillRounded(g, face.x - 2, face.y - 2, face.right() + 2, face.bottom() + 2, Glass.RADIUS_SM, Palette.SURFACE);
        ResourceLocation tex = this.minecraft == null ? null : ShieldTextures.own(this.minecraft, chosen, CustomShield.fit());
        if (tex == null) {
            UiRender.textCentered(g, this.font, "Plain", face, Palette.TEXT_FAINT);
            return;
        }
        int s = ShieldTextures.ownScale();
        Draw.blit(g, tex, face.x, face.y, face.w, face.h, ShieldArt.FACE_X * s, ShieldArt.FACE_Y * s,
                ShieldArt.FACE_W * s, ShieldArt.FACE_H * s, ShieldArt.BASE * s, ShieldArt.BASE * s);
    }

    private String statusLine(String chosen) {
        if (notice != null) return notice;
        if (ShieldTextures.ownProblem() != null) return ShieldTextures.ownProblem();
        if (!chosen.isEmpty() && !CustomShield.active()) return "Turn Custom Shield on to see it on your shield.";
        if (!ShieldShare.status().isEmpty()) return ShieldShare.status();
        return CustomShield.sharing() ? null : "Only you see it; sharing is in the module's settings.";
    }

    @Override
    protected boolean onMouseClicked(double mx, double my, int button) {
        if (list.contains(mx, my)) {
            for (int i = 0; i < rows.size(); i++) {
                if (!rowBox(i).contains(mx, my)) continue;
                notice = null;
                try {
                    if (this.minecraft != null) ShieldMenu.choose(this.minecraft, rows.get(i));
                    save();
                } catch (IllegalArgumentException e) {
                    notice = e.getMessage();
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
        if (this.minecraft != null) dev.breeze.compat.ActiveScreen.set(this.minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
