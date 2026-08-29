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
        // Deliberately empty: this mod exists only to be a realistic host for the injected bridge.
    }

}
