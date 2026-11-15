package dev.breeze;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ClientRegistry;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Breeze on Forge 1.18 to 1.18.2. Forge has no key registration event before
 * 1.19: key bindings are added with ClientRegistry during client setup, so
 * Breeze starts there, on the game's thread (enqueueWork), and hands its
 * bindings over at once.
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
        modBus.addListener(EventPriority.NORMAL, false, FMLClientSetupEvent.class, event -> event.enqueueWork(() -> {
            BreezeInit.run();
            dev.breeze.compat.Keys.registerPending(ClientRegistry::registerKeyBinding);
        }));
    }
}
