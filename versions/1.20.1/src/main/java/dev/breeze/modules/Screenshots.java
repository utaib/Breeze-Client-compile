package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.lwjgl.glfw.GLFW;

public class Screenshots extends Module {

    public Screenshots() {
        super("Screenshots", Category.UTILITY, "Takes a screenshot on its keybind.", GLFW.GLFW_KEY_F8);
    }

    @Override
    protected void onEnable() {
        try {
            Minecraft mc = Minecraft.getInstance();
            Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), component -> {
                if (mc.player != null) mc.player.displayClientMessage(component, false);
            });
        } catch (Throwable ignored) {}
        setStateSilently(false);
    }
}
