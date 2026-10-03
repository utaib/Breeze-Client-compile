package dev.breeze.modules;

import dev.breeze.Category;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

public class SystemResourcesHud extends AbstractHudModule {

    public SystemResourcesHud() {
        super("System Resources", Category.HUD, "Shows RAM and CPU usage.", KEY_NONE, 4, 224);
    }

    private String breeze$mem = "Mem: 0 / 0 MB";
    private String breeze$cpu = "CPU: 0 cores";
    private long breeze$next;

    private void breeze$refresh() {
        long now = System.currentTimeMillis();
        if (now < breeze$next) return;
        breeze$next = now + 500L;
        Runtime r = Runtime.getRuntime();
        long used = (r.totalMemory() - r.freeMemory()) / (1024 * 1024);
        long max = r.maxMemory() / (1024 * 1024);
        breeze$mem = "Mem: " + used + " / " + max + " MB";
        breeze$cpu = "CPU: " + r.availableProcessors() + " cores";
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        breeze$refresh();
        line(g, font, breeze$mem);
        line(g, font, breeze$cpu);
    }
}
