package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;
import net.minecraft.client.Minecraft;
import dev.breeze.compat.Game;
import com.mojang.blaze3d.platform.InputConstants;

public class Screenshots extends Module {

    public Screenshots() {
        super("Screenshots", Category.UTILITY, "Takes a screenshot on its keybind.", InputConstants.KEY_F8);
    }

    @Override
    protected void onEnable() {
        try {
            Minecraft mc = Minecraft.getInstance();
            // Minecraft reports the saved file from its screenshot thread (26.x)
            // or the render thread; chat must only be touched on the render
            // thread, so the message goes there as vanilla's own key does.
            // From the worker it crashed 26.1 and 26.3 ("Rendersystem called
            // from wrong thread") when chat had to bake a new glyph.
            Game.screenshot(mc, null, component -> mc.execute(() -> Game.message(mc, component)));
        } catch (Throwable ignored) {}
        setStateSilently(false);
    }
}
