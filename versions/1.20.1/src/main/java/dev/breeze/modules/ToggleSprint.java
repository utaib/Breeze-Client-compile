package dev.breeze.modules;

import dev.breeze.BreezeClient;
import dev.breeze.Category;
import dev.breeze.Module;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.InputConstants;

public class ToggleSprint extends Module {

    public ToggleSprint() {
        super("Toggle Sprint", Category.UTILITY, "Always sprint.", InputConstants.KEY_V);
    }

    @Override
    protected void onTick(Minecraft mc) {
        if (mc.player == null) return;
        try {
            if (!mc.player.isSprinting()) {
                mc.player.setSprinting(true);
            }
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] ToggleSprint failed: {}", t.toString());
        }
    }
}
