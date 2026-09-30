package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;
import dev.breeze.menu.KeybindSearchScreen;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public class KeybindSearch extends Module {

    public KeybindSearch() {
        super("Keybind Search", Category.UTILITY, "Opens a searchable keybind list.", GLFW.GLFW_KEY_K);
    }

    @Override
    protected void onEnable() {
        try {
            Minecraft.getInstance().setScreen(new KeybindSearchScreen());
        } catch (Throwable ignored) {}
        setStateSilently(false);
    }
}
