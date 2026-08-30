package org.cyclops.clientdevbridge.handler;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import org.cyclops.clientdevbridge.mcadapter.Aim;
import org.cyclops.clientdevbridge.mcadapter.ClientState;
import org.cyclops.clientdevbridge.mcadapter.ClientThread;
import org.cyclops.clientdevbridge.mcadapter.CommandRunner;
import org.cyclops.clientdevbridge.mcadapter.Interaction;
import org.cyclops.clientdevbridge.mcadapter.McAdapter;
import org.cyclops.clientdevbridge.mcadapter.Polling;
import org.cyclops.clientdevbridge.mcadapter.WorldControl;
import org.cyclops.clientdevbridge.mcadapter.WorldQuery;
import org.cyclops.clientdevbridge.protocol.Dispatcher;
import org.cyclops.clientdevbridge.protocol.Json;
import org.cyclops.clientdevbridge.protocol.Params;

import java.nio.file.Path;
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

            // Before anything is left or deleted: a typo'd template name must not cost the caller
            // the world they already had.
            if (template != null) {
                WorldControl.requireTemplate(templatesRoot(projectDir), template);
            }

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
            // Check the name before leaving: a typo must not cost the caller the world it is in.
            return ClientThread.run(() -> WorldControl.requireExists(name))
                    .thenCompose(ignored -> ClientThread.runOnTick(WorldControl::leave))
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
                CommandRunner.Result outcome = CommandRunner.execute(command);
                JsonObject result = Json.object();
                // Success is reported explicitly: a failing command still prints something, so
                // output alone cannot tell a caller whether the scene was actually built.
                result.addProperty("success", outcome.success());
                result.addProperty("value", outcome.value());
                result.add("output", Json.arrayOfStrings(outcome.output()));
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

        // A right-click that is not about opening a GUI: placing a block or a cable part, using a
        // tool, wrenching. screen.open is this plus a wait for a screen, and reports failure when
        // none appears -- which is the wrong contract for every interaction that never opens one.
        dispatcher.register("world.use", raw -> {
            Params params = new Params(raw);
            double[] pos = params.getNumberArray("blockPos", 3);
            BlockPos blockPos = new BlockPos((int) pos[0], (int) pos[1], (int) pos[2]);
            Aim aim = Aim.of(blockPos, params.getString("face", null),
                    params.has("at") ? params.getNumberArray("at", 3) : null);
            boolean approach = params.getBoolean("approach", true);
            boolean sneak = params.getBoolean("sneak", false);
            String hand = params.getEnum("hand", "main", "main", "off");

            // What a use did is only visible as a difference, so both sides of it are recorded.
            // None of them is reliable on its own: in creative nothing leaves the hand, and adding
            // a part to a cable changes neither the block id nor its state -- which is why the
            // interaction's own result is reported alongside them.
            CompletableFuture<JsonObject> snapshotBefore = ClientThread.submit(() -> {
                JsonObject snapshot = Json.object();
                snapshot.addProperty("block", WorldQuery.block(blockPos, false).get("state").getAsString());
                snapshot.addProperty("held", Interaction.describeHeld(Interaction.hand(hand)));
                snapshot.addProperty("screen", ClientState.screenClass());
                // Sneak is read by the server, so it is set before the rotation wait rather than
                // alongside the click, or the click carries the old state.
                if (sneak) {
                    Interaction.setSneaking(true);
                }
                return snapshot;
            });

            java.util.concurrent.atomic.AtomicReference<String> outcome =
                    new java.util.concurrent.atomic.AtomicReference<>("NONE");
            return snapshotBefore.thenCompose(before -> ScreenHandler
                    .aimAndClick(aim, approach,
                            () -> outcome.set(Interaction.describeResult(
                                    Interaction.useOn(aim, Interaction.hand(hand)))))
                    // The result of a use is a server round trip away: a placed part, a changed
                    // block and an opened screen all arrive later than the click returns.
                    .thenCompose(ignored -> McAdapter.tickClock().afterTicks(5))
                    .thenCompose(ignored -> ClientThread.submit(() -> {
                        if (sneak) {
                            Interaction.setSneaking(false);
                        }
                        JsonObject result = Json.object();
                        result.add("pos", Json.arrayOfNumbers(blockPos.getX(), blockPos.getY(), blockPos.getZ()));
                        result.addProperty("face", aim.face().getName());
                        // What the block did with the click. In creative nothing is consumed and a
                        // multipart block changes neither its id nor its state when a part is
                        // added, so this is often the only thing that says the click landed.
                        result.addProperty("result", outcome.get());
                        result.addProperty("blockBefore", before.get("block").getAsString());
                        result.addProperty("blockAfter",
                                WorldQuery.block(blockPos, false).get("state").getAsString());
                        result.addProperty("heldBefore", before.get("held").getAsString());
                        result.addProperty("heldAfter", Interaction.describeHeld(Interaction.hand(hand)));
                        result.addProperty("screenClass", ClientState.screenClass());
                        result.addProperty("screenOpened", ClientState.screenClass() != null
                                && !java.util.Objects.equals(ClientState.screenClass(),
                                        before.get("screen").isJsonNull() ? null : before.get("screen").getAsString()));
                        return result;
                    })));
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
                () -> ClientState.isWorldReadyAt(
                        WorldControl.SPAWN_X, WorldControl.SPAWN_Y, WorldControl.SPAWN_Z),
                LOAD_TIMEOUT_MS,
                "The world '" + name + "' did not finish loading within " + (LOAD_TIMEOUT_MS / 1000)
                        + " seconds. Check 'clientdevbridge logs --gradle' for errors.");
    }

}
