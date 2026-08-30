package org.cyclops.clientdevbridge.handler;

import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;
import org.cyclops.clientdevbridge.mcadapter.ClientState;
import org.cyclops.clientdevbridge.mcadapter.ClientThread;
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
            return ClientThread.run(() -> PlayerControl.teleport(x, y, z, yaw, pitch))
                    .thenCompose(ignored -> McAdapter.tickClock().awaitCondition(
                            () -> PlayerControl.isAt(x, y, z), ARRIVAL_TIMEOUT_TICKS, null))
                    .thenCompose(arrived -> ClientThread.submit(() -> {
                        JsonObject state = playerState();
                        state.addProperty("arrived", arrived);
                        // What was asked for, alongside where the player ended up. Gravity acts
                        // between the two, so the same command reports y=5 or y=4 depending on how
                        // many ticks the round trip took -- which reads as a bug and is not one.
                        state.add("requested", Json.arrayOfNumbers(x, y, z));
                        return state;
                    }));
        });

        dispatcher.register("player.inventory", raw -> ClientThread.submit(PlayerControl::inventory));

        dispatcher.register("player.hotbar", raw -> {
            int slot = new Params(raw).getInt("slot");
            return ClientThread.submit(() -> {
                PlayerControl.selectHotbarSlot(slot);
                JsonObject result = Json.object();
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
