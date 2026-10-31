package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;
import net.minecraft.ChatFormatting;
import dev.breeze.compat.ItemData;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.List;

public class ShulkerTooltips extends Module {

    private static ShulkerTooltips instance;

    public ShulkerTooltips() {
        super("Shulker Tooltips", Category.UTILITY, "Previews shulker box contents on hover.", KEY_NONE);
        instance = this;
    }

    public static boolean active() {
        return instance != null && instance.isEnabled();
    }

    public static void appendTo(ItemStack stack, List<Component> lines) {
        if (!active() || stack == null || stack.isEmpty()) return;
        try {
            if (!(Block.byItem(stack.getItem()) instanceof ShulkerBoxBlock)) return;
            int shown = 0;
            for (ItemStack s : ItemData.shulkerContents(stack)) {
                if (shown >= 5) {
                    lines.add(Component.literal(" ...").withStyle(ChatFormatting.DARK_GRAY));
                    break;
                }
                lines.add(Component.literal(" " + s.getCount() + "x ").append(s.getHoverName()).withStyle(ChatFormatting.GRAY));
                shown++;
            }
        } catch (Throwable ignored) {}
    }
}
