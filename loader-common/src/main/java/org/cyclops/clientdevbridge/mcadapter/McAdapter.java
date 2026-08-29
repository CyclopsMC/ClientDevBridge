package org.cyclops.clientdevbridge.mcadapter;

/**
 * Holds the loader's hook implementation and the shared tick clock.
 *
 * This is the single entry point into the version-sensitive half of the mod; nothing outside
 * this package should reach into {@code net.minecraft} directly.
 *
 * @author rubensworks
 */
public class McAdapter {

    private static IClientHooks hooks;
    private static final TickClock TICK_CLOCK = new TickClock();

    public static void install(IClientHooks clientHooks) {
        hooks = clientHooks;
        clientHooks.registerClientTick(() -> {
            // Blocking actions run first: they pump nested ticks, and the clock has to keep
            // advancing through them so that whoever is waiting on the result still gets ticks.
            ClientThread.drainTickActions();
            TICK_CLOCK.onClientTick();
        });
    }

    public static IClientHooks hooks() {
        if (hooks == null) {
            throw new IllegalStateException("The loader has not installed its ClientDevBridge hooks yet");
        }
        return hooks;
    }

    public static TickClock tickClock() {
        return TICK_CLOCK;
    }

}
