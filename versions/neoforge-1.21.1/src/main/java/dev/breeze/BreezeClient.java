package dev.breeze;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Breeze on NeoForge 1.21.1. As on Forge, nothing starts in the constructor
 * (mods are constructed on worker threads): Breeze starts in NeoForge's key
 * registration event, on the game's thread, before resources load
 * (BreezeInit), and hands its key bindings to that same event.
 */
@Mod(BreezeClient.MOD_ID)
public class BreezeClient {

    public static final String MOD_ID = "breeze";
    public static final Logger LOGGER = LoggerFactory.getLogger("Breeze");

    public BreezeClient(IEventBus modBus) {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            LOGGER.warn("[Breeze] Breeze is a client mod; it does nothing on a server");
            return;
        }
        modBus.addListener(EventPriority.NORMAL, false, RegisterKeyMappingsEvent.class, event -> {
            BreezeInit.run();
            dev.breeze.compat.Keys.registerPending(event::register);
        });
        modBus.addListener(EventPriority.NORMAL, false, FMLClientSetupEvent.class, event -> event.enqueueWork(() -> {
            if (!BreezeInit.done()) {
                LOGGER.warn("[Breeze] Key registration did not reach Breeze; starting at client setup");
                BreezeInit.run();
            }
        }));
    }
}
