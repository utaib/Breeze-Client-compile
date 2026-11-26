package dev.breeze.compat;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.ArrayList;
import java.util.List;

/**
 * Reading items and mounts. This is the 26.1 form: as from 1.20.5 (item data
 * in components, jump strength an ordinary attribute), and a container's
 * items are stored as templates, copied out as stacks.
 */
public final class ItemData {

    private ItemData() {}

    /** A horse's jump strength, the value the jump height formula takes. */
    public static double horseJumpStrength(AbstractHorse horse) {
        return horse.getAttributeValue(Attributes.JUMP_STRENGTH);
    }

    /** The items stored in a shulker box item, in slot order; empty if none. */
    public static List<ItemStack> shulkerContents(ItemStack stack) {
        List<ItemStack> out = new ArrayList<>();
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (contents == null) return out;
        // 26.1: the stored items are templates; this stream copies them out as stacks.
        contents.nonEmptyItemCopyStream().forEach(out::add);
        return out;
    }
}
