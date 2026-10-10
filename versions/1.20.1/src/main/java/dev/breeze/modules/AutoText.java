package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.InputConstants;

public class AutoText extends Module {

    private static AutoText instance;

    private String message = "Powered by Breeze";

    public AutoText() {
        super("Auto Text", Category.CHAT, "Sends a preset chat message on its keybind.", InputConstants.KEY_N);
        instance = this;
    }

    public static AutoText get() {
        return instance;
    }

    public void setMessage(String value) {
        if (value != null) this.message = value;
    }

    @Override
    protected void onEnable() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (message != null && !message.isEmpty()) dev.breeze.compat.Chat.send(mc, message);
        } catch (Throwable ignored) {}
        setStateSilently(false);
    }
}
