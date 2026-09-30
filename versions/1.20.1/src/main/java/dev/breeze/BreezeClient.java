package dev.breeze;

import dev.breeze.config.BreezeConfig;
import dev.breeze.keybind.KeybindManager;
import dev.breeze.modules.ShulkerTooltips;
import dev.breeze.modules.Tooltips;
import dev.breeze.net.BreezePresence;
import dev.breeze.ui.Theme;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BreezeClient implements ClientModInitializer {

    public static final String MOD_ID = "breeze";
    public static final Logger LOGGER = LoggerFactory.getLogger("Breeze");

    @Override
    public void onInitializeClient() {
        LOGGER.info("[Breeze] Initializing on Minecraft {}", net.fabricmc.loader.api.FabricLoader.getInstance()
                .getModContainer("minecraft").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?"));

        ModuleManager.init();
        // Before the player's config is applied: these are the values
        // "Reset" in the module settings goes back to.
        dev.breeze.web.ModuleJson.captureDefaults();
        BreezeConfig.load();
        Theme.load();
        dev.breeze.ui.MenuBg.load();
        dev.breeze.ui.HudLayout.load();
        for (Module m : ModuleManager.getModules()) {
            if (m instanceof dev.breeze.modules.AbstractHudModule h) {
                int[] p = dev.breeze.ui.HudLayout.get(m.getName());
                if (p != null) h.setHudPos(p[0], p[1]);
            }
        }
        Friends.load();
        BreezeUsers.load();
        BreezeTag.load();
        MutedSounds.load();
        dev.breeze.cape.CapeManager.init();
        // Web menu (MCEF) availability check. Entirely guarded: when the MCEF
        // mod is not installed this logs and moves on instead of crashing the
        // client entrypoint, which is what the shipped September jars did.
        dev.breeze.web.WebInit.init();
        // Development self-test; does nothing unless -Dbreeze.autotest is set.
        dev.breeze.devtest.AutoTest.install();
        KeybindManager.registerAll();
        // Starts the game token exchange once, here, on the HTTP executor. The
        // tick handlers below only ever read the cached token and skip a poll
        // while it is missing, so no tick waits on the network for it.
        BreezePresence.register();

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            dev.breeze.web.WebInit.tick();
            KeybindManager.handleTicks(mc);
            BreezeUsers.tick(mc);
            dev.breeze.net.BreezePresence.tick(mc);
            dev.breeze.net.FriendsClient.tick(mc);
            dev.breeze.net.HostManager.tick(mc);
            Friends.tick(mc);
            LastServer.capture(mc.getCurrentServer());
            ModuleManager.tickAll(mc);
        });

        HudRenderCallback.EVENT.register((g, partialTick) -> ModuleManager.renderAll(g, partialTick));
        WorldRenderEvents.AFTER_ENTITIES.register(ModuleManager::renderWorldAll);

        ItemTooltipCallback.EVENT.register((stack, flag, lines) -> {
            ShulkerTooltips.appendTo(stack, lines);
            if (!Tooltips.active() || stack == null || stack.isEmpty()) return;
            try {
                lines.add(Component.literal(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
                        .withStyle(ChatFormatting.DARK_GRAY));
            } catch (Throwable ignored) {}
        });
    }
}
