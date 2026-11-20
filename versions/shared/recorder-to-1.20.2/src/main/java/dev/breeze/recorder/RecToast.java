package dev.breeze.recorder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/** A toast in the corner (Minecraft 1.18.2 to 1.20.2). */
final class RecToast {

    private RecToast() {}

    static void show(Minecraft mc, String title, String text) {
        SystemToast.add(mc.getToasts(), SystemToast.SystemToastIds.PERIODIC_NOTIFICATION, Component.literal(title), Component.literal(text));
    }
}
