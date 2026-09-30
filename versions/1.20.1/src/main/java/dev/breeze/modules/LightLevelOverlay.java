package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;
import dev.breeze.render.WorldRender;
import dev.breeze.render.WorldCtx;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LightLayer;

import java.util.ArrayList;
import java.util.List;

public class LightLevelOverlay extends Module {

    private static final int RADIUS = 7;
    private static final String[] DIGITS = {"0", "1", "2", "3", "4", "5", "6", "7"};

    private final List<int[]> cells = new ArrayList<>();
    private long lastCenter = Long.MIN_VALUE;
    private long nextScan;

    public LightLevelOverlay() {
        super("Light Level Overlay", Category.VISUAL, "Shows block light for mob-spawn checking.", KEY_NONE);
    }

    @Override
    protected void onDisable() {
        cells.clear();
        lastCenter = Long.MIN_VALUE;
        nextScan = 0L;
    }

    private void scan(Minecraft mc, BlockPos center) {
        cells.clear();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos below = new BlockPos.MutableBlockPos();
        int cx = center.getX();
        int cy = center.getY();
        int cz = center.getZ();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                for (int dy = -3; dy <= 2; dy++) {
                    pos.set(cx + dx, cy + dy, cz + dz);
                    if (!mc.level.getBlockState(pos).isAir()) continue;
                    int light = mc.level.getBrightness(LightLayer.BLOCK, pos);
                    if (light > 7) continue;
                    below.set(pos.getX(), pos.getY() - 1, pos.getZ());
                    if (!mc.level.getBlockState(below).isFaceSturdy(mc.level, below, Direction.UP)) continue;
                    cells.add(new int[]{pos.getX(), pos.getY(), pos.getZ(), light});
                }
            }
        }
    }

    @Override
    protected void onWorldRender(WorldCtx ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || ctx.consumers() == null) return;
        BlockPos center = mc.player.blockPosition();
        long key = center.asLong();
        long now = System.currentTimeMillis();
        if (key != lastCenter || now >= nextScan) {
            lastCenter = key;
            nextScan = now + 500L;
            scan(mc, center);
        }
        for (int i = 0; i < cells.size(); i++) {
            int[] c = cells.get(i);
            int light = c[3];
            WorldRender.text(ctx, c[0] + 0.5, c[1] + 0.02, c[2] + 0.5, DIGITS[light], light == 0 ? 0xFFFF5555 : 0xFFFFFF55);
        }
    }
}
