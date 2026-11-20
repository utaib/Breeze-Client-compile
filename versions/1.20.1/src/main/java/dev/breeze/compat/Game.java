package dev.breeze.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.PlayerModelPart;

import java.util.function.Consumer;

/**
 * Small game actions whose calls changed between Minecraft versions: skin
 * layers, the client's time of day, chat lines and screenshots. versions/ has
 * a copy of this file per change; this is the 1.20.1 form.
 */
public final class Game {

    private Game() {}

    /** Shows or hides one skin layer, as the Skin Customization screen does. */
    public static void setModelPart(Options options, PlayerModelPart part, boolean shown) {
        options.toggleModelPart(part, shown);
    }

    /** Sets the time of day the client shows (the server's own time is untouched). */
    public static void setDayTime(ClientLevel level, long time) {
        level.setDayTime(time);
    }

    /** A line in the player's chat, seen only by them; nothing when not in a world. */
    public static void message(Minecraft mc, Component text) {
        if (mc.player != null) mc.player.displayClientMessage(text, false);
    }

    /**
     * Saves a screenshot into the game's screenshots folder, named by Minecraft
     * when fileName is null, and reports the chat line Minecraft makes for it.
     */
    public static void screenshot(Minecraft mc, String fileName, Consumer<Component> done) {
        if (fileName == null) {
            Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), done);
        } else {
            Screenshot.grab(mc.gameDirectory, fileName, mc.getMainRenderTarget(), done);
        }
    }
}
