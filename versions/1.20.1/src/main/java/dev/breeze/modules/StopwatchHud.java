package dev.breeze.modules;

import dev.breeze.Category;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

public class StopwatchHud extends AbstractHudModule {

    private long start;
    private long accumulated;
    private boolean running;

    public StopwatchHud() {
        super("Stopwatch", Category.HUD, "A simple stopwatch.", KEY_NONE, 4, 164);
    }

    @Override
    protected void onEnable() {
        start = System.currentTimeMillis();
        running = true;
    }

    @Override
    protected void onDisable() {
        if (running) {
            accumulated += System.currentTimeMillis() - start;
            running = false;
        }
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        long total = accumulated + (running ? System.currentTimeMillis() - start : 0L);
        line(g, font, "Stopwatch: " + PlaytimeHud.format(total));
    }
}
