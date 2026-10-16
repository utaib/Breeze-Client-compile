package dev.breeze.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.clock.ClockNetworkState;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.entity.player.PlayerModelPart;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Small game actions whose calls changed between Minecraft versions: skin
 * layers, the client's time of day, chat lines and screenshots. versions/ has
 * a copy of this file per change; this is the 26.1 form: time of day is kept
 * by world clocks, and a line for the player alone is a system message.
 */
public final class Game {

    private Game() {}

    /** Shows or hides one skin layer, as the Skin Customization screen does. */
    public static void setModelPart(Options options, PlayerModelPart part, boolean shown) {
        options.setModelPart(part, shown);
    }

    /**
     * Sets the time the client shows on the given clocks (the server's own
     * time is untouched) and holds it there, rate 0, until the server's next
     * time update. The clocks are the ones that update named.
     */
    public static void setDayTime(ClientLevel level, long gameTime, Collection<Holder<WorldClock>> clocks, long time) {
        Map<Holder<WorldClock>, ClockNetworkState> held = new HashMap<>();
        for (Holder<WorldClock> clock : clocks) held.put(clock, new ClockNetworkState(time, 0.0f, 0.0f));
        if (!held.isEmpty()) level.clockManager().handleUpdates(gameTime, held);
    }

    /** A line in the player's chat, seen only by them; nothing when not in a world. */
    public static void message(Minecraft mc, Component text) {
        if (mc.player != null) mc.player.sendSystemMessage(text);
    }

    /**
     * Saves a screenshot into the game's screenshots folder, named by Minecraft
     * when fileName is null, and reports the chat line Minecraft makes for it.
     */
    public static void screenshot(Minecraft mc, String fileName, Consumer<Component> done) {
        if (fileName == null) {
            Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), done);
        } else {
            // 1: full size, not downscaled.
            Screenshot.grab(mc.gameDirectory, fileName, mc.getMainRenderTarget(), 1, done);
        }
    }
}
