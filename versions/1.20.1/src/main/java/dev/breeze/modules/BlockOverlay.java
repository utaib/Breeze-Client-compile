package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;
import dev.breeze.render.WorldCtx;
import dev.breeze.render.WorldRender;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public class BlockOverlay extends Module {

    public BlockOverlay() {
        super("Block Overlay", Category.VISUAL, "Outlines the targeted block.", KEY_NONE);
    }

    @Override
    protected void onWorldRender(WorldCtx ctx) {
        Minecraft mc = Minecraft.getInstance();
        HitResult hr = mc.hitResult;
        if (hr == null || hr.getType() != HitResult.Type.BLOCK || !(hr instanceof BlockHitResult bhr)) return;
        if (ctx.consumers() == null) return;
        BlockPos pos = bhr.getBlockPos();
        WorldRender.lineBox(ctx, new AABB(pos), 0.2f, 1.0f, 0.8f, 1.0f);
    }
}
