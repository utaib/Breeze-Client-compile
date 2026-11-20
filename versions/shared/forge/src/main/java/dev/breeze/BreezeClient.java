package dev.breeze;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Breeze on Forge 1.20.1. Forge constructs mods on worker threads, so nothing
 * starts here: Breeze starts in Forge's key registration event, on the game's
 * thread, before resources load (BreezeInit), and hands its key bindings to
 * that same event. Client setup only catches the case where that event did
 * not reach Breeze.
 */
@Mod(BreezeClient.MOD_ID)
public class BreezeClient {

    public static final String MOD_ID = "breeze";
    public static final Logger LOGGER = LoggerFactory.getLogger("Breeze");

    public BreezeClient() {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            LOGGER.warn("[Breeze] Breeze is a client mod; it does nothing on a server");
            return;
        }
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
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
