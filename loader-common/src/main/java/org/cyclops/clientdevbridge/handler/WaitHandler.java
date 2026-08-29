package org.cyclops.clientdevbridge.handler;

import com.google.gson.JsonObject;
import org.cyclops.clientdevbridge.mcadapter.ClientState;
import org.cyclops.clientdevbridge.mcadapter.McAdapter;
import org.cyclops.clientdevbridge.protocol.Dispatcher;
import org.cyclops.clientdevbridge.protocol.Json;
import org.cyclops.clientdevbridge.protocol.Params;
import org.cyclops.clientdevbridge.protocol.RpcException;

import java.util.function.BooleanSupplier;

/**
 * {@code wait.ticks} and {@code wait.for}.
 *
 * Waiting never blocks the render thread: the condition is evaluated once per client tick from the
 * tick hook, and the caller's future completes from there.
 *
 * @author rubensworks
 */
public class WaitHandler {

    /** A minute of ticks; long enough for any legitimate wait, short enough to not hang an agent. */
    public static final int MAX_TICKS = 20 * 60;

    public static final int DEFAULT_TIMEOUT_MS = 10_000;

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
                JsonObject result = Json.object();
                result.addProperty("tick", tick);
                return result;
            });
        });

        dispatcher.register("wait.for", raw -> {
            Params params = new Params(raw);
            String condition = params.getEnum("condition", "inWorld",
                    "screen", "noScreen", "inWorld", "outOfWorld", "chunkLoaded", "expr");
            long timeoutMs = params.getLong("timeoutMs", DEFAULT_TIMEOUT_MS);
            long timeoutTicks = Math.max(1, timeoutMs / 50);

            BooleanSupplier predicate = predicate(condition, params);
            return McAdapter.tickClock().awaitCondition(predicate, timeoutTicks, null).thenApply(met -> {
                JsonObject result = Json.object();
                result.addProperty("met", met);
                result.addProperty("condition", condition);
                result.addProperty("screenClass", ClientState.screenClass());
                result.addProperty("inWorld", ClientState.inWorld());
                return result;
            });
        });
    }

    private static BooleanSupplier predicate(String condition, Params params) {
        switch (condition) {
            case "screen": {
                String value = params.getString("value");
                return () -> matchesScreen(value);
            }
            case "noScreen":
                return () -> ClientState.screenClass() == null;
            case "inWorld":
                return ClientState::inWorld;
            case "outOfWorld":
                return () -> !ClientState.inWorld();
            case "chunkLoaded": {
                double[] pos = params.getNumberArray("value", 3);
                return () -> ClientState.inWorld()
                        && ClientState.isChunkLoaded((int) pos[0], (int) pos[1], (int) pos[2]);
            }
            case "expr": {
                String expression = params.getString("value");
                return () -> EvalHandler.evaluateAsBoolean(expression);
            }
            default:
                throw RpcException.invalidParams("Unsupported wait condition '" + condition + "'.");
        }
    }

    /**
     * Screens are matched by simple name or by fully-qualified name, and case-insensitively, so a
     * caller can write {@code CraftingScreen} without knowing the package.
     */
    static boolean matchesScreen(String value) {
        String actual = ClientState.screenClass();
        if (actual == null) {
            return false;
        }
        if (actual.equalsIgnoreCase(value)) {
            return true;
        }
        String simple = actual.substring(actual.lastIndexOf('.') + 1);
        return simple.equalsIgnoreCase(value);
    }

}
