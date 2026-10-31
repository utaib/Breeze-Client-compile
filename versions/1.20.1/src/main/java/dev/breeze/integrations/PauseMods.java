package dev.breeze.integrations;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Mod Menu's "Mods" button on the game menu, where Breeze's menu changes left
 * the player without one. Mod Menu adds its own button after the screen is
 * built; one tick later Breeze looks, and adds a button that opens Mod Menu's
 * real list only when Mod Menu's own is not there. Never a second one.
 */
public final class PauseMods {

    /** The game menu with Breeze's buttons on it ({@code PauseScreenMixin}). */
    public interface Host {
        /** True once this build of the screen has been looked at. */
        boolean breeze$modsChecked();

        void breeze$markModsChecked();

        void breeze$addModsButton();
    }

    private PauseMods() {}

    /** Called every client tick; does something once per build of the game menu. */
    public static void tick(Minecraft mc) {
        Screen screen = dev.breeze.compat.ActiveScreen.get(mc);
        if (!(screen instanceof Host host) || host.breeze$modsChecked()) return;
        host.breeze$markModsChecked();
        if (!Integrations.modMenu()) return;
        if (hasModMenuButton(screen.children(), Component.translatable("modmenu.title").getString(), 0)) return;
        host.breeze$addModsButton();
    }

    private static boolean hasModMenuButton(List<? extends GuiEventListener> children, String label, int depth) {
        for (GuiEventListener child : children) {
            if (child.getClass().getName().startsWith("com.terraformersmc.modmenu")) return true;
            if (child instanceof AbstractWidget w && w.getMessage().getString().equals(label)) return true;
            if (depth < 3 && child instanceof ContainerEventHandler c && hasModMenuButton(c.children(), label, depth + 1)) return true;
        }
        return false;
    }

    /** Opens Mod Menu's list over the game menu. */
    public static void open(Minecraft mc, Screen parent) {
        try {
            dev.breeze.compat.ActiveScreen.set(mc, Integrations.screen(Integrations.MOD_MENU, "mods", parent));
        } catch (Integrations.Unavailable u) {
            dev.breeze.BreezeClient.LOGGER.warn("[Breeze] {}", u.getMessage());
        }
    }
}
