package dev.breeze.modules;

import dev.breeze.Category;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

public class ArmorBarHud extends AbstractHudModule {

    private final dev.breeze.settings.Setting.Bool icon =
            add(new dev.breeze.settings.Setting.Bool("icon", "Armour icon", "Armor Bar", true));

    public ArmorBarHud() {
        super("Armor Bar", Category.HUD, "Shows armor points.", KEY_NONE, 4, 124);
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        if (mc.player == null) return;
        int armor = mc.player.getArmorValue();
        int color = armor >= 15 ? 0xFF55FF55 : armor >= 5 ? 0xFFFFFF55 : 0xFFFFFFFF;
        if (icon.value) {
            // Minecraft's own armour point, at its 9 pixel size.
            textureLine(g, font, dev.breeze.ui.GameTextures.ARMOR_ICON,
                    dev.breeze.ui.GameTextures.sources(dev.breeze.ui.GameTextures.ARMOR_ICON), 9, armor + "/20", color);
        } else {
            line(g, font, "Armor: " + armor + "/20", color);
        }
    }
}
