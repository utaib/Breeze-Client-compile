package dev.breeze.cosmetics;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.UUID;

/**
 * Which player holds the shield Minecraft is about to draw (Custom Shield).
 *
 * The shield's own renderer is told only the item (up to 1.21.3) or only its
 * components (from 1.21.4), never who holds it, so the holder is found per
 * rendering era:
 * <ul>
 *   <li>up to 1.21.1: the stack itself is one of a player's two hands
 *   ({@link #holding}), since the game draws the entity's own stacks;</li>
 *   <li>1.21.2 and 1.21.3: the hand layer draws from a render state, so its
 *   player is set around that layer (ShieldLayerMixin), the stack lookup
 *   staying for the first-person hand;</li>
 *   <li>from 1.21.4: the item's render state is tagged when the game fills
 *   it from a player's hand (ShieldResolverMixin), and set around its
 *   drawing (ShieldStateMixin).</li>
 * </ul>
 * Only the render thread draws items, so the current holder is a plain field.
 */
public final class ShieldHolders {

    /** Implemented on Minecraft's item render state from 1.21.4 (ShieldStateMixin). */
    public interface Tagged {
        void breeze$tagShield(UUID holder, String context);
    }

    private static UUID current;
    private static String currentContext;

    private ShieldHolders() {}

    /** The holder of what is drawn now, and how it is shown (an ItemDisplayContext name). */
    public static void enter(UUID holder, String context) {
        current = holder;
        currentContext = context;
    }

    public static void exit() {
        current = null;
        currentContext = null;
    }

    public static UUID current() {
        return current;
    }

    public static String currentContext() {
        return currentContext;
    }

    /** The player holding this very shield stack in either hand, or null. */
    public static UUID holding(Minecraft mc, ItemStack stack) {
        if (mc.level == null || stack == null || stack.getItem() != Items.SHIELD) return null;
        for (Player p : mc.level.players()) {
            if (p.getMainHandItem() == stack || p.getOffhandItem() == stack) return p.getUUID();
        }
        return null;
    }

    /**
     * The holder to tag a render state with: the owner the game names, when
     * it is a player and the stack is in one of their hands; else null.
     */
    public static UUID holderOf(Object owner, ItemStack stack) {
        if (stack == null || stack.getItem() != Items.SHIELD) return null;
        if (owner instanceof Player p && (p.getMainHandItem() == stack || p.getOffhandItem() == stack)) return p.getUUID();
        return null;
    }
}
