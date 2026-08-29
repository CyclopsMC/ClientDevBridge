package org.cyclops.clientdevbridge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import org.cyclops.clientdevbridge.mcadapter.ClientHooksNeoForge;

/**
 * NeoForge client entry point.
 *
 * @author rubensworks
 */
@Mod(value = Reference.MOD_ID, dist = Dist.CLIENT)
public class ClientDevBridgeNeoForge {

    public ClientDevBridgeNeoForge(IEventBus modEventBus) {
        modEventBus.addListener(this::onClientSetup);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ClientDevBridge.start(new ClientHooksNeoForge()));
    }

}
