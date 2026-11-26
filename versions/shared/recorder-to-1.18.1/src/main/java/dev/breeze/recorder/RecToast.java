package dev.breeze.recorder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/** A toast in the corner (Minecraft 1.17 to 1.18.1, which has no periodic notification toast). */
final class RecToast {

    private RecToast() {}

    static void show(Minecraft mc, String title, String text) {
        SystemToast.add(mc.getToasts(), SystemToast.SystemToastIds.WORLD_BACKUP, Component.literal(title), Component.literal(text));
    }
}
