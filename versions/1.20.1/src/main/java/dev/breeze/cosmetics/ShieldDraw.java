package dev.breeze.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.breeze.compat.Tris;
import dev.breeze.modules.CustomShield;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws a shield with a Breeze shield texture (ShieldTextures) where the game
 * would draw its own: the same shape (ShieldMesh) at the same place, through
 * the era's {@link CosmeticRender.Sink}. The per-era mixins (ShieldItemMixin)
 * ask {@link #textureFor} first and leave the shield to Minecraft when it
 * answers null.
 *
 * Only shields in a player's hands in the world: not in inventory slots
 * (from 1.21.6 Minecraft reuses inventory item pictures between items that
 * look alike, so a per-player picture there could show on other shields),
 * nor on armour stands or mobs.
 * An enchanted shield's glint is not drawn over a custom picture.
 */
public final class ShieldDraw {

    private static final int WHITE = 0xFFFFFFFF;

    /** Custom shields drawn since start, for the self-test, in all and per display context. */
    public static volatile int draws;
    public static final Map<String, Integer> DRAWS_BY_CONTEXT = new ConcurrentHashMap<>();

    private ShieldDraw() {}

    /**
     * The texture to draw this holder's shield with, or null for Minecraft's
     * own: your own picture (Custom Shield on, a picture chosen), or another
     * Breeze player's shared one once downloaded (unless turned off).
     */
    public static ResourceLocation textureFor(Minecraft mc, UUID holder, String context) {
        if (holder == null || mc.player == null || "GUI".equals(context)) return null;
        try {
            if (holder.equals(mc.player.getUUID())) return CustomShield.ownTexture(mc);
            if (!CustomShield.showOthers()) return null;
            return ShieldTextures.remote(CosmeticState.get(holder).shield);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Writes the shield through the pose the game would draw its own with. */
    public static void draw(PoseStack poseStack, CosmeticRender.Sink sink, ResourceLocation texture,
                            int light, int overlay, String context) {
        sink.draw(poseStack, texture, false, (vc, pose) -> {
            for (ShieldMesh.Quad q : ShieldMesh.quads()) {
                float[] c = q.corners();
                for (int i = 0; i < 4; i++) {
                    int k = i * 5;
                    Tris.vertex(vc, pose, c[k], c[k + 1], c[k + 2], WHITE, c[k + 3], c[k + 4],
                            overlay, light, q.nx(), q.ny(), q.nz());
                }
            }
        });
        draws++;
        DRAWS_BY_CONTEXT.merge(context == null ? "unknown" : context, 1, Integer::sum);
    }
}
