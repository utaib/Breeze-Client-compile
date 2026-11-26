package dev.breeze.recorder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/** A toast in the corner (Minecraft 1.20.3 to 1.21.1). */
final class RecToast {

    private RecToast() {}

    static void show(Minecraft mc, String title, String text) {
        SystemToast.add(mc.getToasts(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION, Component.literal(title), Component.literal(text));
    }
}
