package dev.breeze.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The base of every Breeze screen. Minecraft has changed how a screen receives
 * input several times (a horizontal scroll axis in 1.20.2, event objects in
 * 1.21.9, resize without the Minecraft argument), and each change broke every
 * screen that overrides an input method. So Breeze screens override the on*
 * methods here, which keep one shape, and this class (one copy per input
 * change, in the versions/ folders) turns Minecraft's calls into them.
 *
 * An on* method that does not handle the input passes it on with the matching
 * super* method, the way an override would call super. This is the form
 * for 1.20.2 to 1.21.8: scrolling has a horizontal axis.
 */
public abstract class BreezeScreen extends Screen {

    protected BreezeScreen(Component title) {
        super(title);
    }

    // ── What Breeze screens override (GUI coordinates) ─────────────────────

    protected boolean onMouseClicked(double x, double y, int button) {
        return superMouseClicked(x, y, button);
    }

    protected boolean onMouseReleased(double x, double y, int button) {
        return superMouseReleased(x, y, button);
    }

    protected boolean onMouseDragged(double x, double y, int button, double dx, double dy) {
        return superMouseDragged(x, y, button, dx, dy);
    }

    /** scrollY is the usual wheel; scrollX is 0 where Minecraft has no horizontal axis. */
    protected boolean onMouseScrolled(double x, double y, double scrollX, double scrollY) {
        return superMouseScrolled(x, y, scrollX, scrollY);
    }

    protected void onMouseMoved(double x, double y) {
        super.mouseMoved(x, y);
    }

    protected boolean onKeyPressed(int key, int scanCode, int modifiers) {
        return superKeyPressed(key, scanCode, modifiers);
    }

    protected boolean onKeyReleased(int key, int scanCode, int modifiers) {
        return superKeyReleased(key, scanCode, modifiers);
    }

    protected boolean onCharTyped(int codePoint, int modifiers) {
        return superCharTyped(codePoint, modifiers);
    }

    /**
     * Whether Minecraft draws its own background (panorama, dirt or blur)
     * under this screen. From 1.20.2 Minecraft's render() draws it first;
     * Breeze screens paint their own and say no, which keeps the look they
     * have on 1.20.1.
     */
    protected boolean vanillaBackground() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (vanillaBackground()) super.renderBackground(g, mouseX, mouseY, partialTick);
    }

    /** After Minecraft has laid the screen out again for a new window size. */
    protected void onResized(int width, int height) {
    }

    // ── Minecraft's default handling ───────────────────────────────────────

    protected final boolean superMouseClicked(double x, double y, int button) {
        return super.mouseClicked(x, y, button);
    }

    protected final boolean superMouseReleased(double x, double y, int button) {
        return super.mouseReleased(x, y, button);
    }

    protected final boolean superMouseDragged(double x, double y, int button, double dx, double dy) {
        return super.mouseDragged(x, y, button, dx, dy);
    }

    protected final boolean superMouseScrolled(double x, double y, double scrollX, double scrollY) {
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    protected final boolean superKeyPressed(int key, int scanCode, int modifiers) {
        return super.keyPressed(key, scanCode, modifiers);
    }

    protected final boolean superKeyReleased(int key, int scanCode, int modifiers) {
        return super.keyReleased(key, scanCode, modifiers);
    }

    protected final boolean superCharTyped(int codePoint, int modifiers) {
        return Character.isBmpCodePoint(codePoint) && super.charTyped((char) codePoint, modifiers);
    }

    // ── Minecraft's calls (1.20.2 to 1.21.8) ──────────────────────────────

    @Override
    public final boolean mouseClicked(double x, double y, int button) {
        return onMouseClicked(x, y, button);
    }

    @Override
    public final boolean mouseReleased(double x, double y, int button) {
        return onMouseReleased(x, y, button);
    }

    @Override
    public final boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return onMouseDragged(x, y, button, dx, dy);
    }

    @Override
    public final boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        return onMouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public final void mouseMoved(double x, double y) {
        onMouseMoved(x, y);
    }

    @Override
    public final boolean keyPressed(int key, int scanCode, int modifiers) {
        return onKeyPressed(key, scanCode, modifiers);
    }

    @Override
    public final boolean keyReleased(int key, int scanCode, int modifiers) {
        return onKeyReleased(key, scanCode, modifiers);
    }

    @Override
    public final boolean charTyped(char c, int modifiers) {
        return onCharTyped(c, modifiers);
    }

    @Override
    public final void resize(Minecraft mc, int width, int height) {
        super.resize(mc, width, height);
        onResized(width, height);
    }
}
