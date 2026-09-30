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
            previous = mc.options.showSubtitles().get();
            mc.options.showSubtitles().set(true);
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onDisable() {
        try {
            Minecraft.getInstance().options.showSubtitles().set(previous);
        } catch (Throwable ignored) {}
    }
}
