package dev.breeze.recorder;

import net.minecraft.client.player.LocalPlayer;

/** The player's state the recorder reads and sets (Minecraft 26.3 and later: swings are described by a swing state). */
final class RecPlayer {

    private RecPlayer() {}

    static int slot(LocalPlayer p) {
        return p.getInventory().getSelectedSlot();
    }

    static void setSlot(LocalPlayer p, int slot) {
        p.getInventory().setSelectedSlot(slot);
    }

    static boolean onGround(LocalPlayer p) {
        return p.onGround();
    }

    static boolean swinging(LocalPlayer p) {
        return p.isSwinging();
    }

    static boolean offHandSwing(LocalPlayer p) {
        return p.getCurrentSwing() != null && p.getCurrentSwing().hand() == net.minecraft.world.InteractionHand.OFF_HAND;
    }

    /** Swings an arm the way a click does. */
    static void swing(LocalPlayer p, boolean offHand) {
        p.swing(offHand ? net.minecraft.world.InteractionHand.OFF_HAND : net.minecraft.world.InteractionHand.MAIN_HAND,
                net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
    }
}
