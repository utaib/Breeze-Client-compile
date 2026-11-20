package dev.breeze.modules;

import dev.breeze.Category;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;

public class ItemDespawnTimerHud extends AbstractHudModule {

    public ItemDespawnTimerHud() {
        super("Item Despawn Timer", Category.HUD, "Shows item despawn countdown.", KEY_NONE, 4, 364);
    }

    private int breeze$maxAge = -1;

    @Override
    protected void onTick(Minecraft mc) {
        if (mc.level == null) {
            breeze$maxAge = -1;
            return;
        }
        int maxAge = -1;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof ItemEntity) {
                int a = ((ItemEntity) e).getAge();
                if (a > maxAge) maxAge = a;
            }
        }
        breeze$maxAge = maxAge;
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        if (mc.level == null) return;
        int maxAge = breeze$maxAge;
        if (maxAge < 0) {
            line(g, font, "Items: none");
        } else {
            int remaining = Math.max(0, 6000 - maxAge);
            int sec = remaining / 20;
            line(g, font, String.format("Despawn: %d:%02d", sec / 60, sec % 60), remaining < 600 ? 0xFFFF5555 : 0xFFFFFFFF);
        }
    }
}
