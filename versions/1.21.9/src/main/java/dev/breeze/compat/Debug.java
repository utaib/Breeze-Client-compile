package dev.breeze.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;

/**
 * Minecraft's debug views that modules switch on. This is the 1.21.9 form:
 * each view is a debug screen entry with a status, and switching one off
 * restores the status the player had given it.
 */
public final class Debug {

    private static DebugScreenEntryStatus hitboxesBefore;

    private Debug() {}

    /** Entity hitboxes, as F3+B shows them. */
    public static void setHitboxes(Minecraft mc, boolean shown) {
        if (shown) {
            if (hitboxesBefore == null) hitboxesBefore = mc.debugEntries.getStatus(DebugScreenEntries.ENTITY_HITBOXES);
            mc.debugEntries.setStatus(DebugScreenEntries.ENTITY_HITBOXES, DebugScreenEntryStatus.ALWAYS_ON);
        } else {
            mc.debugEntries.setStatus(DebugScreenEntries.ENTITY_HITBOXES,
                    hitboxesBefore != null ? hitboxesBefore : DebugScreenEntryStatus.NEVER);
            hitboxesBefore = null;
        }
    }
}
