package org.cyclops.clientdevbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate that keeps this dev-only mod inert everywhere else.
 *
 * @author rubensworks
 */
class BridgeConfigTest {

    @Test
    void isDisabledUnlessExplicitlyEnabled() {
        // The whole safety story rests on this default.
        assertEquals(false, new BridgeConfig(false, false, 1, null).isEnabled());
    }

    @Test
    void fallsBackToTheDefaultPortForAnythingUnusable() {
        assertEquals(Reference.DEFAULT_PORT, BridgeConfig.parsePort(null));
        assertEquals(Reference.DEFAULT_PORT, BridgeConfig.parsePort(""));
        assertEquals(Reference.DEFAULT_PORT, BridgeConfig.parsePort("not a port"));
        assertEquals(Reference.DEFAULT_PORT, BridgeConfig.parsePort("0"));
        assertEquals(Reference.DEFAULT_PORT, BridgeConfig.parsePort("70000"));
    }

    @Test
    void acceptsAValidPort() {
        assertEquals(30000, BridgeConfig.parsePort(" 30000 "));
    }

    @Test
    void resolvesTheProjectDirectoryToAnAbsolutePath() {
        assertTrue(BridgeConfig.parseProjectDir("/tmp/../tmp/project").isAbsolute());
        assertEquals("/tmp/project", BridgeConfig.parseProjectDir("/tmp/../tmp/project").toString());
        assertTrue(BridgeConfig.parseProjectDir(null).isAbsolute());
    }

}
