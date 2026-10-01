package dev.breeze.modules;

import dev.breeze.Category;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.effect.MobEffectInstance;

import java.util.Collection;

public class PotionEffectsHud extends AbstractHudModule {

    public PotionEffectsHud() {
        super("Potion Effects", Category.HUD, "Lists active effects.", KEY_NONE, 4, 244);
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        if (mc.player == null) return;
        Collection<MobEffectInstance> effects = mc.player.getActiveEffects();
        if (effects.isEmpty()) {
            line(g, font, "Effects: none");
            return;
        }
        line(g, font, "Effects: " + effects.size());
        for (MobEffectInstance e : effects) {
            try {
                int amp = e.getAmplifier() + 1;
                int sec = e.getDuration() / 20;
                line(g, font, "  " + roman(amp) + " (" + sec + "s)");
            } catch (Throwable ignored) {}
        }
    }

    private static String roman(int n) {
        switch (n) {
            case 1: return "I";
            case 2: return "II";
            case 3: return "III";
            case 4: return "IV";
            default: return "+" + n;
        }
    }
}
