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
            Game.screenshot(mc, null, component -> Game.message(mc, component));
        } catch (Throwable ignored) {}
        setStateSilently(false);
    }
}
