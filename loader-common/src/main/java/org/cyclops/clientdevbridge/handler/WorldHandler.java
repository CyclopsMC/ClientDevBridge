package org.cyclops.clientdevbridge.handler;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import org.cyclops.clientdevbridge.mcadapter.ClientState;
import org.cyclops.clientdevbridge.mcadapter.ClientThread;
import org.cyclops.clientdevbridge.mcadapter.CommandRunner;
import org.cyclops.clientdevbridge.mcadapter.Polling;
import org.cyclops.clientdevbridge.mcadapter.WorldControl;
import org.cyclops.clientdevbridge.mcadapter.WorldQuery;
import org.cyclops.clientdevbridge.protocol.Dispatcher;
import org.cyclops.clientdevbridge.protocol.Json;
import org.cyclops.clientdevbridge.protocol.Params;
import org.cyclops.clientdevbridge.protocol.RpcException;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * {@code world.*}: creating, loading, leaving and inspecting the singleplayer world.
 *
 * @author rubensworks
 */
public class WorldHandler {

    public static final String DEFAULT_WORLD = "clientdevbridge";
    /** Generating and joining a world is slow under software rendering; be generous. */
    private static final long LOAD_TIMEOUT_MS = 180_000;

    public static void register(Dispatcher dispatcher, Path projectDir) {
        dispatcher.register("world.reset", raw -> {
            Params params = new Params(raw);
            String name = params.getString("name", DEFAULT_WORLD);
            String template = params.getString("template", null);
            String setup = params.getString("setup", null);

            return ClientThread.runOnTick(WorldControl::leave)
                    .thenCompose(ignored -> awaitOutOfWorld())
                    .thenCompose(ignored -> ClientThread.runOnTick(() -> {
                        WorldControl.delete(name);
                        if (template != null) {
                            WorldControl.copyTemplate(templatesRoot(projectDir), template, name);
                            WorldControl.load(name);
                        } else {
                            WorldControl.createSuperflat(name);
                        }
                    }))
                    .thenCompose(ignored -> awaitInWorld(name))
                    .thenCompose(ignored -> ClientThread.submit(() -> {
                        WorldControl.applyDeterminism(setup);
                        JsonObject result = Json.object();
                        result.addProperty("world", name);
                        result.addProperty("template", template);
                        result.add("spawn", Json.arrayOfNumbers(
                                WorldControl.SPAWN_X, WorldControl.SPAWN_Y, WorldControl.SPAWN_Z));
                        result.addProperty("seed", WorldControl.SEED);
                        result.addProperty("platformY", WorldControl.PLATFORM_Y);
                        result.addProperty("platformRadius", WorldControl.PLATFORM_RADIUS);
                        return result;
                    }));
        });

        dispatcher.register("world.load", raw -> {
            String name = new Params(raw).getString("name");
            return ClientThread.runOnTick(WorldControl::leave)
                    .thenCompose(ignored -> awaitOutOfWorld())
                    .thenCompose(ignored -> ClientThread.runOnTick(() -> WorldControl.load(name)))
                    .thenCompose(ignored -> awaitInWorld(name))
                    .thenApply(ignored -> {
                        JsonObject result = Json.object();
                        result.addProperty("world", name);
                        return result;
                    });
        });

        dispatcher.register("world.leave", raw -> ClientThread.runOnTick(WorldControl::leave)
                .thenCompose(ignored -> awaitOutOfWorld())
                .thenApply(ignored -> Json.object()));

        dispatcher.register("world.list", raw -> ClientThread.submit(() -> {
            JsonObject result = Json.object();
            result.add("worlds", Json.arrayOfStrings(WorldControl.listWorlds()));
            return result;
        }));

        dispatcher.register("world.command", raw -> {
            String command = new Params(raw).getString("command");
            return ClientThread.submit(() -> {
                List<String> output = CommandRunner.run(command);
                JsonObject result = Json.object();
                result.add("output", Json.arrayOfStrings(output));
                return result;
            });
        });

        dispatcher.register("world.block", raw -> {
            Params params = new Params(raw);
            int x = params.getInt("x");
            int y = params.getInt("y");
            int z = params.getInt("z");
            boolean nbt = params.getBoolean("nbt", false);
            return ClientThread.submit(() -> WorldQuery.block(new BlockPos(x, y, z), nbt));
        });
    }

    private static Path templatesRoot(Path projectDir) {
        return projectDir.resolve("clientdevbridge").resolve("templates");
    }

    private static CompletableFuture<Boolean> awaitOutOfWorld() {
        return Polling.await(() -> !ClientState.inWorld(), 20_000,
                "The client did not leave the world within 20 seconds. See 'clientdevbridge logs --gradle'.");
    }

    /**
     * Waits until the world is genuinely ready to be looked at.
     *
     * "In a world" is not enough: the client stays on the "Loading terrain" screen for a while
     * after the level exists, and a screenshot taken then is of that screen, not of the world.
     * Waiting for the screen to clear as well is what makes the very next screenshot meaningful —
     * and it is exactly the kind of race that silently poisons a golden image.
     */
    private static CompletableFuture<Boolean> awaitInWorld(String name) {
        return Polling.await(
                () -> {
                    net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
                    return ClientState.inWorld()
                            && minecraft.getSingleplayerServer() != null
                            && minecraft.screen == null
                            && minecraft.level != null
                            && minecraft.level.isLoaded(new BlockPos(
                                    WorldControl.SPAWN_X, WorldControl.SPAWN_Y, WorldControl.SPAWN_Z));
                },
                LOAD_TIMEOUT_MS,
                "The world '" + name + "' did not finish loading within " + (LOAD_TIMEOUT_MS / 1000)
                        + " seconds. Check 'clientdevbridge logs --gradle' for errors.");
    }

}
