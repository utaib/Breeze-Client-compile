package dev.breeze;

import dev.breeze.compat.Platform;
import dev.breeze.config.BreezeConfig;
import dev.breeze.keybind.KeybindManager;
import dev.breeze.modules.ShulkerTooltips;
import dev.breeze.modules.Tooltips;
import dev.breeze.net.BreezePresence;
import dev.breeze.ui.Theme;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;

/**
 * Breeze's start on Forge and NeoForge: the same steps, in the same order,
 * as the Fabric build's BreezeClient.onInitializeClient. The loader's entry
 * point (BreezeClient, one per loader) runs this from its key registration
 * event, which both loaders fire on the game's thread while Minecraft is
 * constructed, after every mod is constructed and before resources load:
 * the moment Fabric runs its client entry points. Breeze's key bindings are
 * created here, so they exist when that event registers them.
 */
public final class BreezeInit {

    private static boolean done;

    private BreezeInit() {}

    /** Whether Breeze has started (the self-test and the bridge ask). */
    public static synchronized boolean done() {
        return done;
    }

    public static synchronized void run() {
        if (done) return;
        done = true;
        BreezeClient.LOGGER.info("[Breeze] Initializing on Minecraft {} with {} {}", Platform.minecraftVersion(),
                Platform.name(), Platform.loaderVersion());

        dev.breeze.music.Lyrics.clientVersion(Platform.modVersion(BreezeClient.MOD_ID));
        ModuleManager.init();
        dev.breeze.web.ModuleJson.captureDefaults();
        for (Module m : ModuleManager.getModules()) {
            if (m.onByDefault()) m.setStateSilently(true);
        }
        BreezeConfig.load();
        dev.breeze.commands.CommandKeyRunner.load();
        Theme.load();
        dev.breeze.ui.MenuBg.load();
        dev.breeze.ui.HudLayout.load();
        for (Module m : ModuleManager.getModules()) {
            if (m instanceof dev.breeze.modules.AbstractHudModule h) {
                dev.breeze.hud.HudPlacement p = dev.breeze.ui.HudLayout.get(m.getName());
                if (p != null) h.setPlacement(p);
            }
        }
        Friends.load();
        BreezeUsers.load();
        BreezeTag.load();
        MutedSounds.load();
        dev.breeze.cape.CapeManager.init();
        dev.breeze.web.WebInit.init();
        dev.breeze.devtest.AutoTest.install();
        KeybindManager.registerAll();
        BreezePresence.register();

        // Fabric's "client started": the first tick, once every mod is up.
        boolean[] started = {false};
        Platform.onEndTick(mc -> {
            if (started[0]) return;
            started[0] = true;
            KeybindManager.yieldSharedDefaults(mc);
            dev.breeze.integrations.Integrations.discover();
            dev.breeze.integrations.Integrations.writeReport();
        });

        Platform.onEndTick(mc -> {
            dev.breeze.web.WebInit.tick();
            KeybindManager.handleTicks(mc);
            dev.breeze.commands.CommandKeyRunner.tick(mc);
            dev.breeze.render.Frame.ticked();
            BreezeUsers.tick(mc);
            dev.breeze.net.BreezePresence.tick(mc);
            dev.breeze.net.FriendsClient.tick(mc);
            dev.breeze.net.HostManager.tick(mc);
            Friends.tick(mc);
            LastServer.capture(mc.getCurrentServer());
            ModuleManager.tickAll(mc);
            dev.breeze.music.Music.tick(mc);
            dev.breeze.integrations.PauseMods.tick(mc);
            dev.breeze.cosmetics.ShieldShare.tick(mc);
        });

        dev.breeze.compat.Hooks.hud(ModuleManager::renderAll);
        dev.breeze.compat.Hooks.world(ModuleManager::renderWorldAll);
        dev.breeze.compat.Hooks.blockOutline(() -> !dev.breeze.modules.BlockOverlay.replacesVanilla());
        dev.breeze.compat.Hooks.tooltips((stack, lines) -> {
            ShulkerTooltips.appendTo(stack, lines);
            if (!Tooltips.active() || stack == null || stack.isEmpty()) return;
            try {
                lines.add(Component.literal(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
                        .withStyle(ChatFormatting.DARK_GRAY));
            } catch (Throwable ignored) {}
        });
    }
}
