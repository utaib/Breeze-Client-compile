package dev.breeze.compat;

import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Reading items and mounts. Item data moved from NBT to components in 1.20.5,
 * so these live here (versions/ has a copy per change). This is the 1.20.1
 * form.
 */
public final class ItemData {

    private ItemData() {}

    /** A horse's jump strength, the value the jump height formula takes. */
    public static double horseJumpStrength(AbstractHorse horse) {
        return horse.getCustomJump();
    }

    /** The items stored in a shulker box item, in slot order; empty if none. */
    public static List<ItemStack> shulkerContents(ItemStack stack) {
        List<ItemStack> out = new ArrayList<>();
        CompoundTag tag = BlockItem.getBlockEntityData(stack);
        if (tag == null || !tag.contains("Items")) return out;
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, items);
        for (ItemStack s : items) if (!s.isEmpty()) out.add(s);
        return out;
    }
}
