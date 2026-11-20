package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;
import dev.breeze.menu.KeybindSearchScreen;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.InputConstants;

public class KeybindSearch extends Module {

    public KeybindSearch() {
        super("Keybind Search", Category.UTILITY, "Opens a searchable keybind list.", InputConstants.KEY_K);
    }

    @Override
    protected void onEnable() {
        try {
            dev.breeze.compat.ActiveScreen.set(Minecraft.getInstance(), new KeybindSearchScreen());
        } catch (Throwable ignored) {}
        setStateSilently(false);
    }
}
