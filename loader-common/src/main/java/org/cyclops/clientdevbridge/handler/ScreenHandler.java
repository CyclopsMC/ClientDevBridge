package org.cyclops.clientdevbridge.handler;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import org.cyclops.clientdevbridge.mcadapter.ClientState;
import org.cyclops.clientdevbridge.mcadapter.ClientThread;
import org.cyclops.clientdevbridge.mcadapter.McAdapter;
import org.cyclops.clientdevbridge.mcadapter.ScreenControl;
import org.cyclops.clientdevbridge.protocol.Dispatcher;
import org.cyclops.clientdevbridge.protocol.Json;
import org.cyclops.clientdevbridge.protocol.Params;
import org.cyclops.clientdevbridge.protocol.RpcException;

/**
 * {@code screen.open} and {@code screen.close}.
 *
 * @author rubensworks
 */
public class ScreenHandler {

    /** A block's GUI opens on a server round trip, so allow a couple of seconds. */
    private static final int OPEN_TIMEOUT_TICKS = 20 * 5;

    /** Ticks to let a teleport reach the server and come back before clicking. */
    private static final int APPROACH_SETTLE_TICKS = 5;

    public static void register(Dispatcher dispatcher) {
        dispatcher.register("screen.open", raw -> {
            Params params = new Params(raw);
            if (!params.has("blockPos")) {
                throw RpcException.invalidParams(
                        "Parameter 'blockPos' is required: screen.open opens a block's GUI by right-clicking it.");
            }
            double[] pos = params.getNumberArray("blockPos", 3);
            boolean approach = params.getBoolean("approach", true);
            BlockPos blockPos = new BlockPos((int) pos[0], (int) pos[1], (int) pos[2]);
            String before = ClientState.screenClass();

            // Approaching teleports through the integrated server, so the click has to wait for the
            // new position to round-trip; clicking in the same tick is still done from the old one,
            // and the server rejects it as out of reach without saying so.
            java.util.concurrent.CompletableFuture<?> positioned = approach
                    ? ClientThread.run(() -> ScreenControl.approach(blockPos))
                            .thenCompose(ignored -> McAdapter.tickClock().afterTicks(APPROACH_SETTLE_TICKS))
                    : java.util.concurrent.CompletableFuture.completedFuture(null);

            return positioned
                    .thenCompose(ignored -> ClientThread.run(() -> ScreenControl.openBlock(blockPos, false)))
                    .thenCompose(ignored -> McAdapter.tickClock().awaitCondition(
                            () -> ClientState.screenClass() != null
                                    && !java.util.Objects.equals(ClientState.screenClass(), before),
                            OPEN_TIMEOUT_TICKS, null))
                    .thenCompose(opened -> ClientThread.submit(() -> {
                        JsonObject result = Json.object();
                        result.addProperty("screenClass", ClientState.screenClass());
                        result.addProperty("opened", Boolean.TRUE.equals(opened));
                        if (!Boolean.TRUE.equals(opened)) {
                            result.addProperty("hint", ScreenControl.describeFailedOpen(blockPos));
                        }
                        return result;
                    }));
        });

        dispatcher.register("screen.close", raw -> ClientThread.run(ScreenControl::close)
                .thenApply(ignored -> {
                    JsonObject result = Json.object();
                    result.addProperty("screenClass", (String) null);
                    return result;
                }));
    }

}
