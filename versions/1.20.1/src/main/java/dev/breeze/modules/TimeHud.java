package dev.breeze.modules;

import dev.breeze.Category;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.time.LocalTime;

public class TimeHud extends AbstractHudModule {

    public TimeHud() {
        super("Time", Category.HUD, "Shows the current time of day.", KEY_NONE, 4, 124);
    }

    private String breeze$time = "Time: 00:00:00";
    private long breeze$next;

    private String breeze$clock() {
        long now = System.currentTimeMillis();
        if (now >= breeze$next) {
            breeze$next = now + 250L;
            LocalTime t = LocalTime.now();
            StringBuilder sb = new StringBuilder(16).append("Time: ");
            breeze$pad(sb, t.getHour()).append(':');
            breeze$pad(sb, t.getMinute()).append(':');
            breeze$pad(sb, t.getSecond());
            breeze$time = sb.toString();
        }
        return breeze$time;
    }

    private static StringBuilder breeze$pad(StringBuilder sb, int v) {
        if (v < 10) sb.append('0');
        return sb.append(v);
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        line(g, font, breeze$clock());
    }
}
