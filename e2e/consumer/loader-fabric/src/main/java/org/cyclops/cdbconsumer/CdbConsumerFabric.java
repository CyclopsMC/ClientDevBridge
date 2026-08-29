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
        // Touching the probe here loads its class, so the hotswap end-to-end test has a class
        // that is actually resident in the running client to redefine.
        org.slf4j.LoggerFactory.getLogger(CdbConsumer.MOD_ID).info("CdbConsumer probe: {}", HotswapProbe.marker());
    }

}
