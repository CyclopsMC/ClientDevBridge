package org.cyclops.clientdevbridge.handler;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import org.cyclops.clientdevbridge.mcadapter.ClientState;
import org.cyclops.clientdevbridge.mcadapter.ClientThread;
import org.cyclops.clientdevbridge.mcadapter.McAdapter;
import org.cyclops.clientdevbridge.mcadapter.PlayerControl;
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

    /** How long to give the approach teleport to round-trip through the integrated server. */
    private static final int APPROACH_TIMEOUT_TICKS = 40;

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
            // and the server rejects it as out of reach without saying so. A fixed number of ticks
            // is not enough to wait: right after a world is created the server thread has plenty
            // else to do, and the round trip is exactly as slow as it happens to be. The client's
            // position only moves when the server's teleport arrives, so arriving is the signal.
            double[] target = ScreenControl.approachTarget(blockPos);
            java.util.concurrent.CompletableFuture<?> positioned = approach
                    ? ClientThread.run(() -> ScreenControl.approach(blockPos))
                            .thenCompose(ignored -> McAdapter.tickClock().awaitCondition(
                                    () -> PlayerControl.isAt(target[0], target[1], target[2]),
                                    APPROACH_TIMEOUT_TICKS, null))
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
