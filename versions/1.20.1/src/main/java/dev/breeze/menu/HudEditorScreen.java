package dev.breeze.menu;

import dev.breeze.compat.BreezeScreen;
import dev.breeze.ui.Palette;
import dev.breeze.Module;
import dev.breeze.ModuleManager;
import dev.breeze.modules.AbstractHudModule;
import dev.breeze.ui.HudLayout;
import dev.breeze.ui.Theme;
import dev.breeze.ui.UiRender;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class HudEditorScreen extends BreezeScreen {

    private final Screen parent;
    private AbstractHudModule dragging;
    private int dragOffX;
    private int dragOffY;

    public HudEditorScreen(Screen parent) {
        super(Component.literal("HUD Editor"));
        this.parent = parent;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, Palette.alpha(Palette.BG, 0x90));

        ModuleManager.renderAll(g, partialTick);

        for (Module m : ModuleManager.getModules()) {
            if (!(m instanceof AbstractHudModule h) || !m.isEnabled()) continue;
            int x = h.getHudX();
            int y = h.getHudY();
            int w = h.getHudW();
            int hh = h.getHudH();
            boolean over = mouseX >= x - 2 && mouseX <= x + w + 2 && mouseY >= y - 2 && mouseY <= y + hh + 2;
            g.fill(x - 2, y - 2, x + w + 2, y + hh + 2, over ? Palette.BORDER_HOVER : Palette.BORDER);
            UiRender.border(g, x - 2, y - 2, w + 4, hh + 4, over ? Theme.primary() : Palette.alpha(0xFFFFFFFF, 0x60));
        }

        g.drawCenteredString(this.font, Component.literal("§bHUD Editor §7— drag elements · Esc to save"), this.width / 2, this.height - 16, Palette.TEXT_PRIMARY);
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    protected boolean onMouseClicked(double mx, double my, int button) {
        if (button == 0) {
            for (Module m : ModuleManager.getModules()) {
                if (!(m instanceof AbstractHudModule h) || !m.isEnabled()) continue;
                int x = h.getHudX();
                int y = h.getHudY();
                if (mx >= x - 2 && mx <= x + h.getHudW() + 2 && my >= y - 2 && my <= y + h.getHudH() + 2) {
                    dragging = h;
                    dragOffX = (int) mx - x;
                    dragOffY = (int) my - y;
                    return true;
                }
            }
        }
        return superMouseClicked(mx, my, button);
    }

    @Override
    protected boolean onMouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging != null) {
            // Clamp the whole element on screen, not just its corner.
            //
            // This used to bound the top-left to width - 4, which keeps four
            // pixels of a module visible and lets the rest hang off the edge.
            // A wide readout dragged right became a sliver you had to know was
            // there to grab again. Bounding by the measured size, which already
            // accounts for the module's scale, means what you can see is what
            // you can still pick up.
            int w = dragging.getHudW();
            int h = dragging.getHudH();
            int nx = Math.max(0, Math.min(this.width - w, (int) mx - dragOffX));
            int ny = Math.max(0, Math.min(this.height - h, (int) my - dragOffY));
            dragging.setHudPos(nx, ny);
            return true;
        }
        return superMouseDragged(mx, my, button, dx, dy);
    }

    @Override
    protected boolean onMouseReleased(double mx, double my, int button) {
        if (dragging != null) {
            HudLayout.set(dragging.getName(), dragging.getHudX(), dragging.getHudY());
            dragging = null;
            return true;
        }
        return superMouseReleased(mx, my, button);
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
