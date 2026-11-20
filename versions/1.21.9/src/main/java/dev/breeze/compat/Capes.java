package dev.breeze.compat;

import net.minecraft.client.player.AbstractClientPlayer;

/**
 * The cape texture Minecraft itself would draw for a player, as a string, for
 * the self-test's in-world check. This is the 1.21.9 and later form: the
 * PlayerSkin record holds client assets, the cape's texture path inside.
 */
public final class Capes {

    private Capes() {}

    public static String vanillaCape(AbstractClientPlayer player) {
        var cape = player.getSkin().cape();
        return cape == null ? null : cape.texturePath().toString();
    }
}
