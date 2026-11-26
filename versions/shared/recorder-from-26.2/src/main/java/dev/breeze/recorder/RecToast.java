package dev.breeze.recorder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/** A toast in the corner (Minecraft 26.2 and later: the toasts are the HUD's). */
final class RecToast {

    private RecToast() {}

    static void show(Minecraft mc, String title, String text) {
        SystemToast.add(mc.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION, Component.literal(title), Component.literal(text));
    }
}
