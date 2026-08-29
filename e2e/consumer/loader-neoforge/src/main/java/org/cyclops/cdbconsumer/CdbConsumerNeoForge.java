package org.cyclops.cdbconsumer;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/**
 * NeoForge entry point of the end-to-end consumer fixture.
 *
 * @author rubensworks
 */
@Mod(CdbConsumer.MOD_ID)
public class CdbConsumerNeoForge {

    public CdbConsumerNeoForge(IEventBus modEventBus) {
        // Touching the probe here loads its class, so the hotswap end-to-end test has a class
        // that is actually resident in the running client to redefine.
        org.slf4j.LoggerFactory.getLogger(CdbConsumer.MOD_ID).info("CdbConsumer probe: {}", HotswapProbe.marker());
    }

}
