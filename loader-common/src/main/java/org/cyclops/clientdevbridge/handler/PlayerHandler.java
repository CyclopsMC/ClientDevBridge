package org.cyclops.clientdevbridge.handler;

import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;
import org.cyclops.clientdevbridge.mcadapter.ClientState;
import org.cyclops.clientdevbridge.mcadapter.ClientThread;
import org.cyclops.clientdevbridge.mcadapter.InputControl;
import org.cyclops.clientdevbridge.mcadapter.Keys;
import org.cyclops.clientdevbridge.mcadapter.McAdapter;
import org.cyclops.clientdevbridge.mcadapter.PlayerControl;
import org.cyclops.clientdevbridge.protocol.Dispatcher;
import org.cyclops.clientdevbridge.protocol.Json;
import org.cyclops.clientdevbridge.protocol.Params;
import org.cyclops.clientdevbridge.protocol.RpcException;

/**
 * {@code player.*}: where the player is looking, standing, and what it is holding.
 *
 * @author rubensworks
 */
public class PlayerHandler {

    /** How long to wait for a teleport to round-trip through the integrated server. */
    private static final int ARRIVAL_TIMEOUT_TICKS = 40;

    /** Fifteen seconds of walking, which is a long way and still bounded. */
    private static final int WALK_TIMEOUT_TICKS = 300;

    public static void register(Dispatcher dispatcher) {
        dispatcher.register("player.look", raw -> {
            Params params = new Params(raw);
            if (params.has("at")) {
                double[] at = params.getNumberArray("at", 3);
                return ClientThread.run(() -> PlayerControl.lookAt(new Vec3(at[0], at[1], at[2])))
                        .thenCompose(ignored -> ClientThread.submit(PlayerHandler::playerState));
            }
            if (!params.has("yaw") && !params.has("pitch")) {
                throw RpcException.invalidParams("player.look needs either 'at' as [x, y, z], or 'yaw' and 'pitch'.");
            }
            return ClientThread.submit(() -> {
                float yaw = (float) params.getDouble("yaw", ClientState.requirePlayer().getYRot());
                float pitch = (float) params.getDouble("pitch", ClientState.requirePlayer().getXRot());
                PlayerControl.look(yaw, pitch);
                return playerState();
            });
        });

        dispatcher.register("player.teleport", raw -> {
            Params params = new Params(raw);
            double x = params.getDouble("x");
            double y = params.getDouble("y");
            double z = params.getDouble("z");
            Float yaw = params.has("yaw") ? (float) params.getDouble("yaw") : null;
            Float pitch = params.has("pitch") ? (float) params.getDouble("pitch") : null;

            // Teleporting goes through the integrated server, so the client only moves once the
            // position packet comes back. Returning before then reports the old position and, far
            // worse, lets the next screenshot catch the camera mid-move -- which silently poisons
            // a golden image recorded straight after a teleport.
            //
            // Waiting for the landing and not merely the arrival, because a target in the air is
            // reached long before it is held: the player is still falling, and a reply sent then is
            // true for one tick and wrong for every screenshot after it.
            return ClientThread.run(() -> PlayerControl.teleport(x, y, z, yaw, pitch))
                    .thenCompose(ignored -> McAdapter.tickClock().awaitCondition(
                            () -> PlayerControl.isSettledAt(x, y, z), ARRIVAL_TIMEOUT_TICKS, null))
                    .thenCompose(arrived -> ClientThread.submit(() -> {
                        JsonObject state = playerState();
                        state.addProperty("arrived", arrived);
                        // What was asked for, alongside where the player ended up. Gravity acts
                        // between the two, so the same command reports y=5 or y=4 depending on how
                        // many ticks the round trip took -- which reads as a bug and is not one.
                        state.add("requested", Json.arrayOfNumbers(x, y, z));
                        // Only ever true when the wait timed out, and then it is the whole
                        // explanation: nothing is holding the player up, so the position in this
                        // reply is already out of date and will keep going.
                        state.addProperty("falling", PlayerControl.isFalling());
                        return state;
                    }));
        });

        // Walking, for when the movement itself is the thing being tested -- stepping onto a drop
        // to pick it up, say. Doing it by hand meant resetting the pitch (walking forward while
        // looking down walks into the ground) and then guessing a tick count, which is dead
        // reckoning: nothing says how far twenty ticks goes.
        dispatcher.register("player.walkTo", raw -> {
            Params params = new Params(raw);
            double x = params.getDouble("x");
            double z = params.getDouble("z");
            double within = params.getDouble("within", 0.6d);
            int timeoutTicks = params.getInt("timeoutTicks", WALK_TIMEOUT_TICKS);

            return ClientThread.run(() -> {
                PlayerControl.faceHorizontally(x, z);
                InputControl.setKeyHeld(Keys.toBinding("W"), true);
            }).thenCompose(ignored -> McAdapter.tickClock().awaitCondition(
                    () -> {
                        // Re-aimed every tick: the player drifts, and a heading fixed at the start
                        // walks past anything it does not hit exactly.
                        PlayerControl.faceHorizontally(x, z);
                        return PlayerControl.hasReached(x, z, within);
                    }, timeoutTicks, null))
            .thenCompose(arrived -> ClientThread.<Object>submit(() -> {
                InputControl.setKeyHeld(Keys.toBinding("W"), false);
                JsonObject state = playerState();
                state.addProperty("arrived", arrived);
                state.add("requested", Json.arrayOfNumbers(x, z));
                return state;
            }));
        });

        dispatcher.register("player.inventory", raw -> ClientThread.submit(PlayerControl::inventory));

        dispatcher.register("player.hotbar", raw -> {
            int slot = new Params(raw).getInt("slot");
            return ClientThread.submit(() -> {
                PlayerControl.selectHotbarSlot(slot);
                // What is now held, so a caller does not have to follow every selection with an
                // inventory read to find out whether it picked up the item it meant to.
                JsonObject result = PlayerControl.describeStack(slot,
                        ClientState.requirePlayer().getInventory().getItem(slot));
                result.addProperty("selected", slot);
                return result;
            });
        });
    }

    private static JsonObject playerState() {
        JsonObject result = Json.object();
        var player = ClientState.requirePlayer();
        result.add("pos", Json.arrayOfNumbers(player.getX(), player.getY(), player.getZ()));
        result.addProperty("yaw", player.getYRot());
        result.addProperty("pitch", player.getXRot());
        return result;
    }

}
