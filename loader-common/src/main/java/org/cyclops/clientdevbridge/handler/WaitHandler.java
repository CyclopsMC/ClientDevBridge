package org.cyclops.clientdevbridge.handler;

import org.cyclops.clientdevbridge.mcadapter.McAdapter;
import org.cyclops.clientdevbridge.protocol.Dispatcher;
import org.cyclops.clientdevbridge.protocol.Params;
import org.cyclops.clientdevbridge.protocol.RpcException;

/**
 * {@code wait.ticks}: block the caller, never the render thread, for a number of client ticks.
 *
 * @author rubensworks
 */
public class WaitHandler {

    /** A minute of ticks; long enough for any legitimate wait, short enough to not hang an agent. */
    public static final int MAX_TICKS = 20 * 60;

    public static void register(Dispatcher dispatcher) {
        dispatcher.register("wait.ticks", raw -> {
            Params params = new Params(raw);
            int ticks = params.getInt("ticks");
            if (ticks < 0) {
                throw RpcException.invalidParams("Parameter 'ticks' must not be negative, but was " + ticks);
            }
            if (ticks > MAX_TICKS) {
                throw RpcException.invalidParams("Parameter 'ticks' must be at most " + MAX_TICKS
                        + " (one minute), but was " + ticks);
            }
            return McAdapter.tickClock().afterTicks(ticks).thenApply(tick -> {
                com.google.gson.JsonObject result = new com.google.gson.JsonObject();
                result.addProperty("tick", tick);
                return result;
            });
        });
    }

}
