package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;
import dev.breeze.compat.Game;
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
                Game.setModelPart(mc.options, part, true);
            }
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onDisable() {
        try {
            Minecraft mc = Minecraft.getInstance();
            for (PlayerModelPart part : PlayerModelPart.values()) {
                Game.setModelPart(mc.options, part, wasEnabled.contains(part));
            }
        } catch (Throwable ignored) {}
    }
}
