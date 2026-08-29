package org.cyclops.clientdevbridge;

import net.fabricmc.api.ClientModInitializer;
import org.cyclops.clientdevbridge.mcadapter.ClientHooksFabric;

/**
 * Fabric client entry point.
 *
 * @author rubensworks
 */
public class ClientDevBridgeFabric implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientDevBridge.start(new ClientHooksFabric());
    }

}
