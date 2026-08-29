package org.cyclops.clientdevbridge.mcadapter;

import java.util.List;

/**
 * The only thing the loaders have to provide.
 *
 * Everything else the bridge needs can be reached through plain {@code net.minecraft} classes,
 * so this interface is deliberately tiny: screen changes and world joins/leaves are derived by
 * comparing state on each tick rather than by subscribing to loader-specific events, which keeps
 * the loader surface to a single callback on both Fabric and NeoForge.
 *
 * @author rubensworks
 */
public interface IClientHooks {

    /**
     * Registers a callback to run at the end of every client tick, on the client thread.
     */
    void registerClientTick(Runnable listener);

    /**
     * A short identifier of the loader, reported in the {@code hello} handshake.
     */
    String getLoaderName();

    /**
     * This mod's own version, reported in the {@code hello} handshake so the CLI can tell the user
     * which mod build it is driving.
     */
    String getModVersion();

    /**
     * The ids of every loaded mod, so an agent can confirm the consumer mod it cares about is present.
     */
    List<String> getLoadedModIds();

}
