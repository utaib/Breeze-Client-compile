package dev.breeze.modules;

import dev.breeze.Category;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public class BlockIndicatorHud extends AbstractHudModule {

    public BlockIndicatorHud() {
        super("Block Indicator", Category.HUD, "Shows the block you are aiming at.", KEY_NONE, 4, 84);
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        if (mc.level == null) return;
        HitResult hr = mc.hitResult;
        if (hr == null || hr.getType() != HitResult.Type.BLOCK || !(hr instanceof BlockHitResult bhr)) {
            line(g, font, "Block: -");
            return;
        }
        BlockState state = mc.level.getBlockState(bhr.getBlockPos());
        line(g, font, "Block: " + state.getBlock().getName().getString());
    }
}
