package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;
import dev.breeze.render.WorldCtx;
import dev.breeze.render.WorldRender;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

public class LootBeams extends Module {

    public LootBeams() {
        super("Loot Beams", Category.VISUAL, "Renders a beam over dropped items.", KEY_NONE);
    }

    @Override
    protected void onWorldRender(WorldCtx ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || ctx.consumers() == null) return;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof ItemEntity)) continue;
            double x = e.getX();
            double y = e.getY();
            double z = e.getZ();
            WorldRender.lineBox(ctx, new AABB(x - 0.03, y, z - 0.03, x + 0.03, y + 1.2, z + 0.03), 1.0f, 0.85f, 0.1f, 1.0f);
        }
    }
}
