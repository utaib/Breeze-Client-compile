package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.PlayerModelPart;

import java.util.EnumSet;

public class PlayerModel extends Module {

    private final EnumSet<PlayerModelPart> wasEnabled = EnumSet.noneOf(PlayerModelPart.class);

    public PlayerModel() {
        super("Player Model", Category.VISUAL, "Enables all skin layers.", KEY_NONE);
    }

    @Override
    protected void onEnable() {
        try {
            Minecraft mc = Minecraft.getInstance();
            wasEnabled.clear();
            for (PlayerModelPart part : PlayerModelPart.values()) {
                if (mc.options.isModelPartEnabled(part)) wasEnabled.add(part);
                mc.options.toggleModelPart(part, true);
            }
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onDisable() {
        try {
            Minecraft mc = Minecraft.getInstance();
            for (PlayerModelPart part : PlayerModelPart.values()) {
                mc.options.toggleModelPart(part, wasEnabled.contains(part));
            }
        } catch (Throwable ignored) {}
    }
}
