package dev.breeze.web;

import com.google.gson.JsonElement;
import dev.breeze.bridge.Router;
import net.minecraft.client.gui.GuiGraphics;

/**
 * BreezeBrowser where there is no embedded browser: {@link #open} always
 * answers null, which BreezeWebScreen already treats as "fall back to the
 * native menu". The instance methods exist so the screen compiles unchanged;
 * no instance is ever created. See WebInit in this folder.
 */
public final class BreezeBrowser {

    private BreezeBrowser() {}

    static BreezeBrowser open(Router router, int guiWidth, int guiHeight) {
        router.close();
        return null;
    }

    public static int live() {
        return 0;
    }

    public void emit(String type, JsonElement payload) {
    }

    void mouseMoved(double x, double y) {
    }

    void mousePressed(double x, double y, int button) {
    }

    void mouseReleased(double x, double y, int button) {
    }

    void mouseScrolled(double x, double y, double amount) {
    }

    void keyPressed(int key, int scanCode, int modifiers) {
    }

    void keyReleased(int key, int scanCode, int modifiers) {
    }

    void charTyped(char c, int modifiers) {
    }

    void resize(int guiWidth, int guiHeight) {
    }

    boolean painted() {
        return false;
    }

    boolean pageAnswered() {
        return false;
    }

    long ageMillis() {
        return 0;
    }

    void render(GuiGraphics g, int guiWidth, int guiHeight) {
    }

    void close() {
    }

    boolean isClosed() {
        return true;
    }
}
