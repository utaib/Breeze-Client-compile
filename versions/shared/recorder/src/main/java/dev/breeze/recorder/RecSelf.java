package dev.breeze.recorder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.nio.ByteBuffer;

/**
 * The player's own state each tick, which the server never sends back to
 * them: where they are and look, their pose, the selected slot, an arm
 * swing, and the screen they have open. Written as a SELF record of fixed
 * layout (version byte first); watching applies it to the player that
 * plays the recording, so the camera is their own view.
 */
final class RecSelf {

    static final int SIZE = 1 + 3 * 8 + 4 * 4 + 1 + 1 + 1;
    private static final int FORMAT = 1;

    static final int ON_GROUND = 1, CROUCHING = 2, SPRINTING = 4, SWING_MAIN = 8, SWING_OFF = 16, SWIMMING = 32, FLYING = 64;

    private boolean wasSwinging;

    void reset() {
        wasSwinging = false;
    }

    /** This tick's state, as bytes. */
    byte[] capture(LocalPlayer p, Minecraft mc) {
        ByteBuffer b = ByteBuffer.allocate(SIZE);
        b.put((byte) FORMAT);
        b.putDouble(p.getX()).putDouble(p.getY()).putDouble(p.getZ());
        b.putFloat(p.getYRot()).putFloat(p.getXRot()).putFloat(p.getYHeadRot()).putFloat(p.yBodyRot);
        int flags = 0;
        if (RecPlayer.onGround(p)) flags |= ON_GROUND;
        if (p.isCrouching()) flags |= CROUCHING;
        if (p.isSprinting()) flags |= SPRINTING;
        if (p.isSwimming()) flags |= SWIMMING;
        if (p.getAbilities().flying) flags |= FLYING;
        // A swing starts this tick: the arm goes down once per click, as it did.
        boolean swinging = RecPlayer.swinging(p);
        if (swinging && !wasSwinging) flags |= RecPlayer.offHandSwing(p) ? SWING_OFF : SWING_MAIN;
        wasSwinging = swinging;
        b.put((byte) flags);
        b.put((byte) RecPlayer.slot(p));
        b.put((byte) (dev.breeze.compat.ActiveScreen.get(mc) == null ? 0 : 1));
        return b.array();
    }

    /** One tick of state read back. */
    record State(double x, double y, double z, float yRot, float xRot, float headRot, float bodyRot, int flags, int slot,
                 boolean screen) {
        static State read(byte[] data) {
            if (data.length < SIZE || data[0] != FORMAT) return null;
            ByteBuffer b = ByteBuffer.wrap(data, 1, data.length - 1);
            return new State(b.getDouble(), b.getDouble(), b.getDouble(), b.getFloat(), b.getFloat(), b.getFloat(),
                    b.getFloat(), b.get() & 0xFF, b.get() & 0xFF, b.get() != 0);
        }
    }
}
