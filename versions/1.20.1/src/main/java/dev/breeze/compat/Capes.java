package dev.breeze.compat;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;

/**
 * The cape texture Minecraft itself would draw for a player, as a string, for
 * the self-test's in-world check. Where it lives moved (a PlayerSkin record in
 * 1.20.2, a client asset in 1.21.9), so versions/ has a copy per form; this is
 * the form up to 1.20.1.
 */
public final class Capes {

    private Capes() {}

    public static String vanillaCape(AbstractClientPlayer player) {
        ResourceLocation cape = player.getCloakTextureLocation();
        return cape == null ? null : cape.toString();
    }
}
