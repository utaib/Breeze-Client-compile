package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;
import net.minecraft.client.Minecraft;

public class SubtitlesToggle extends Module {

    private boolean previous;

    public SubtitlesToggle() {
        super("Subtitles Toggle", Category.UTILITY, "Turns sound subtitles on.", KEY_NONE);
    }

    @Override
    protected void onEnable() {
        try {
            Minecraft mc = Minecraft.getInstance();
            previous = dev.breeze.compat.Toggles.subtitles(mc);
            dev.breeze.compat.Toggles.setSubtitles(mc, true);
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onDisable() {
        try {
            dev.breeze.compat.Toggles.setSubtitles(Minecraft.getInstance(), previous);
        } catch (Throwable ignored) {}
    }
}
