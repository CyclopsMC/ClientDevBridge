package org.cyclops.clientdevbridge.handler;

import org.cyclops.clientdevbridge.mcadapter.ClientState;
import org.cyclops.clientdevbridge.mcadapter.ClientThread;
import org.cyclops.clientdevbridge.mcadapter.McAdapter;
import org.cyclops.clientdevbridge.protocol.Dispatcher;

/**
 * {@code status}: a cheap snapshot of where the client currently is.
 *
 * @author rubensworks
 */
public class StatusHandler {

    public static void register(Dispatcher dispatcher) {
        dispatcher.register("status", params ->
                ClientThread.submit(() -> ClientState.status(McAdapter.tickClock().getTick())));
    }

}
