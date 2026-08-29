package org.cyclops.cdbconsumer;

import net.fabricmc.api.ModInitializer;

/**
 * Fabric entry point of the end-to-end consumer fixture.
 *
 * @author rubensworks
 */
public class CdbConsumerFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        // Deliberately empty: this mod exists only to be a realistic host for the injected bridge.
    }

}
