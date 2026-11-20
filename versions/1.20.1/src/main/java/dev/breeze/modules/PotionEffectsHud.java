package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.settings.Setting;
import dev.breeze.ui.GameTextures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;

import java.util.Collection;

/**
 * Your active effects, one per line: Minecraft's own icon for the effect, its
 * name in your language, the level and the time left. Everything comes from
 * the effect itself (its translation key names both the text and the icon),
 * so a version's or a resource pack's own pictures are the ones shown.
 */
public class PotionEffectsHud extends AbstractHudModule {

    private static final String GROUP = "Potion Effects";
    private final Setting.Bool icons = add(new Setting.Bool("icons", "Effect icons", GROUP, true));
    private final Setting.Bool names = add(new Setting.Bool("names", "Effect names", GROUP, true));

    public PotionEffectsHud() {
        super("Potion Effects", Category.HUD, "Lists your active effects with their icon, level and time left.", KEY_NONE, 4, 244);
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        if (mc.player == null) return;
        Collection<MobEffectInstance> effects = mc.player.getActiveEffects();
        if (effects.isEmpty()) {
            line(g, font, "Effects: none");
            return;
        }
        if (!icons.value && !names.value) line(g, font, "Effects: " + effects.size());
        for (MobEffectInstance e : effects) {
            try {
                String key = e.getDescriptionId();
                String text = (names.value ? Component.translatable(key).getString() + " " : "")
                        + roman(e.getAmplifier() + 1) + "  " + time(e.getDuration());
                if (icons.value) textureLine(g, font, "effect:" + key, GameTextures.effect(key), 16, text, 0xFFFFFFFF);
                else line(g, font, (names.value ? "" : "  ") + text);
            } catch (Throwable ignored) {}
        }
    }

    /** Ticks left as m:ss; an effect without an end (a beacon's, a command's) says so. */
    static String time(int ticks) {
        if (ticks < 0) return "infinite";
        int sec = ticks / 20;
        return sec / 60 + ":" + (sec % 60 < 10 ? "0" : "") + sec % 60;
    }

    private static String roman(int n) {
        switch (n) {
            case 1: return "I";
            case 2: return "II";
            case 3: return "III";
            case 4: return "IV";
            case 5: return "V";
            default: return String.valueOf(n);
        }
    }
}
