package dev.breeze.compat;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;

/**
 * The cape texture Minecraft itself would draw for a player, as a string, for
 * the self-test's in-world check. This is the 1.20.2 to 1.21.8 form: the
 * player's textures are one PlayerSkin record.
 */
public final class Capes {

    private Capes() {}

    public static String vanillaCape(AbstractClientPlayer player) {
        ResourceLocation cape = player.getSkin().capeTexture();
        return cape == null ? null : cape.toString();
    }
}
