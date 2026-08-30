package org.cyclops.clientdevbridge.handler;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import org.cyclops.clientdevbridge.mcadapter.Aim;
import org.cyclops.clientdevbridge.mcadapter.ClientState;
import org.cyclops.clientdevbridge.mcadapter.ClientThread;
import org.cyclops.clientdevbridge.mcadapter.Interaction;
import org.cyclops.clientdevbridge.mcadapter.McAdapter;
import org.cyclops.clientdevbridge.mcadapter.PlayerControl;
import org.cyclops.clientdevbridge.mcadapter.ScreenControl;
import org.cyclops.clientdevbridge.protocol.Dispatcher;
import org.cyclops.clientdevbridge.protocol.Json;
import org.cyclops.clientdevbridge.protocol.Params;
import org.cyclops.clientdevbridge.protocol.RpcException;

import java.util.concurrent.CompletableFuture;

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
            Aim aim = Aim.of(blockPos, params.getString("face", null),
                    params.has("at") ? params.getNumberArray("at", 3) : null);
            // The screen *instance*, not its class name: re-opening the same kind of screen is a
            // perfectly ordinary thing to do, and comparing names would report it as nothing
            // having happened.
            net.minecraft.client.gui.screens.Screen before = ClientState.screen();

            return aimAndClick(aim, approach, () -> ScreenControl.openBlock(aim))
                    .thenCompose(ignored -> McAdapter.tickClock().awaitCondition(
                            () -> ClientState.screen() != null && ClientState.screen() != before,
                            OPEN_TIMEOUT_TICKS, null))
                    .thenCompose(opened -> ClientThread.submit(() -> {
                        JsonObject result = Json.object();
                        result.addProperty("screenClass", ClientState.screenClass());
                        result.addProperty("opened", Boolean.TRUE.equals(opened));
                        if (!Boolean.TRUE.equals(opened)) {
                            result.addProperty("hint", ScreenControl.describeFailedOpen(aim));
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

    /**
     * Gets the player into position, waits for the server to agree, and then clicks.
     *
     * Two round trips have to complete before the click, and skipping either one produces a click
     * the server quietly drops or evaluates against stale state:
     *
     * <ol>
     *   <li>The teleport. The server ignores interactions from a client it is still awaiting a
     *       teleport confirmation from. A fixed number of ticks is not enough to wait -- right
     *       after a world is created the server thread has plenty else to do -- so the arrival
     *       itself is the signal.</li>
     *   <li>The rotation. A multipart block re-raytraces from the server's copy of the player, and
     *       rotation only reaches the server on the next movement packet. The teleport carries the
     *       intended angles so the server is right immediately; the client is re-aimed on arrival
     *       so it is exact.</li>
     * </ol>
     */
    static CompletableFuture<?> aimAndClick(Aim aim, boolean approach, Runnable click) {
        double[] target = Interaction.approachTarget(aim);
        CompletableFuture<?> positioned = approach
                ? ClientThread.submit(() -> Interaction.approach(aim))
                        .thenCompose(teleported -> Boolean.TRUE.equals(teleported)
                                ? McAdapter.tickClock().awaitCondition(
                                        () -> PlayerControl.isAt(target[0], target[1], target[2]),
                                        APPROACH_TIMEOUT_TICKS, null)
                                // Nothing was teleported, so there is nothing to wait for and no
                                // confirmation pending.
                                : CompletableFuture.completedFuture(null))
                : CompletableFuture.completedFuture(null);
        return positioned
                // Aiming happens after the arrival, not before it: lookAt measures from where the
                // player is, and while the teleport is in flight that is still the old position.
                .thenCompose(ignored -> ClientThread.run(() -> Interaction.aim(aim)))
                .thenCompose(ignored -> McAdapter.tickClock().awaitCondition(
                        Interaction::serverSeesRotation, Interaction.rotationTimeoutTicks(), null))
                .thenCompose(ignored -> ClientThread.run(click));
    }

}
