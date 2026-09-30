package dev.breeze.modules;

import dev.breeze.hud.Fmt;
import dev.breeze.Category;
import dev.breeze.Module;
import dev.breeze.render.WorldRender;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public class DamageIndicator extends Module {

    private final Map<Integer, Float> lastHealth = new HashMap<>();
    private int breeze$pruneTicks;
    private final CopyOnWriteArrayList<Popup> popups = new CopyOnWriteArrayList<>();

    public DamageIndicator() {
        super("Damage Indicator", Category.VISUAL, "Floating damage numbers on entities.", KEY_NONE);
    }

    @Override
    protected void onDisable() {
        lastHealth.clear();
        popups.clear();
    }

    @Override
    protected void onTick(Minecraft mc) {
        if (mc.level == null) return;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity le) || le == mc.player) continue;
            float hp = le.getHealth();
            Float prev = lastHealth.get(e.getId());
            if (prev != null && hp < prev - 0.01f) {
                popups.add(new Popup(le.getX(), le.getY() + le.getBbHeight() + 0.5, le.getZ(), prev - hp, System.currentTimeMillis()));
            }
            lastHealth.put(e.getId(), hp);
        }
        long now = System.currentTimeMillis();
        popups.removeIf(p -> now - p.start > 1000);
        if (++breeze$pruneTicks >= 200) {
            breeze$pruneTicks = 0;
            lastHealth.keySet().removeIf(id -> mc.level.getEntity(id) == null);
        }
    }

    @Override
    protected void onWorldRender(WorldRenderContext ctx) {
        if (ctx.consumers() == null) return;
        long now = System.currentTimeMillis();
        for (Popup p : popups) {
            double rise = (now - p.start) / 1000.0 * 0.5;
            WorldRender.text(ctx, p.x, p.y + rise, p.z, "-" + Fmt.d1(p.dmg), 0xFFFF5555);
        }
    }

    private static final class Popup {
        final double x;
        final double y;
        final double z;
        final float dmg;
        final long start;

        Popup(double x, double y, double z, float dmg, long start) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dmg = dmg;
            this.start = start;
        }
    }
}
