package dev.breeze.modules;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import dev.breeze.Category;
import dev.breeze.packs.PackOrder;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.Pack;

import java.util.Collection;

public class PackDisplayHud extends AbstractHudModule {

    /** The selection the name was read from (Minecraft replaces the list when it changes). */
    private Collection<Pack> readFrom;
    private String name = "Default";

    public PackDisplayHud() {
        super("Pack Display", Category.HUD, "Shows the resource pack on top, the one that wins.", KEY_NONE, 4, 244);
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        try {
            Collection<Pack> selected = mc.getResourcePackRepository().getSelectedPacks();
            if (selected != readFrom) {
                readFrom = selected;
                name = topName(selected);
            }
        } catch (Throwable ignored) {}
        line(g, font, "Pack: " + name);
    }

    /**
     * The top pack a player chose. The game's own and each mod's packs
     * (Fabric gives every mod one, fixed in place) are not choices, so they
     * are skipped; a server's pack is named as one.
     */
    private static String topName(Collection<Pack> selected) {
        String top = "Default";
        for (Pack p : selected) {
            String id = p.getId();
            if (id.equals("server") || id.startsWith("server/")) top = "Server pack";
            else if (!p.isRequired() && !p.isFixedPosition()) top = PackOrder.plain(p.getTitle().getString(), 40).replace('\n', ' ');
        }
        return top;
    }
}
