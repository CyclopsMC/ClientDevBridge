package org.cyclops.clientdevbridge.mcadapter;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.client.Minecraft;
import org.cyclops.clientdevbridge.protocol.RpcException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Runs commands on the integrated server and collects what they printed.
 *
 * Vanilla sends command feedback to the player as chat, which the client would have to scrape.
 * Instead the command is dispatched through a {@link CommandSource} that simply appends every
 * message to a list, so the caller gets the exact output the command produced.
 *
 * @author rubensworks
 */
public class CommandRunner {

    /**
     * The outcome of one command.
     *
     * Success is reported separately from the output because a failing command still prints
     * something -- "Unknown block type ..." -- and a caller that only looks at the output cannot
     * tell a built scene from a scene that was never built.
     */
    public record Result(boolean success, int value, List<String> output, String thread) {
    }

    /**
     * Runs a command, keeping only its output. Prefer {@link #runChecked} where failure matters.
     */
    public static List<String> run(String command) {
        return execute(command).output();
    }

    /**
     * Runs a command and fails the request if the command itself failed.
     */
    public static Result runChecked(String command) {
        Result result = execute(command);
        if (!result.success()) {
            throw RpcException.illegalState("The command '" + command + "' failed: "
                    + (result.output().isEmpty() ? "no output" : String.join(" ", result.output())));
        }
        return result;
    }

    /**
     * @param command the command, with or without a leading slash
     * @return the command's success flag, result value, and feedback messages in order
     */
    public static Result execute(String command) {
        MinecraftServer server = requireServer();
        String normalised = command.startsWith("/") ? command.substring(1) : command;
        if (normalised.isBlank()) {
            throw RpcException.invalidParams("Parameter 'command' must not be empty.");
        }

        // Commands belong to the server thread, and every caller here is on the client thread.
        //
        // Running them where the caller happened to be was a data race against the tick: the game
        // log said "[Render thread/ERROR] [minecraft/Commands]", an Integrated Dynamics cable's
        // collision code threw ConcurrentModificationException mid-tick and took the client with
        // it, and a command that read blocks straight back saw them half-built -- "No part state
        // for part ... Part container: null" for cables that had in fact been placed. Anything
        // touching world state from two threads at once can do that; commands touch a lot of it.
        //
        // isSameThread first, because a command run from a server-side context -- a callback, or a
        // command that runs another -- would otherwise deadlock waiting for the thread it is on.
        if (server.isSameThread()) {
            return performOnServerThread(server, normalised);
        }
        try {
            // Bounded: this blocks the client thread, so a wedged server has to surface as an
            // error rather than as a frozen game with no explanation.
            return server.submit(() -> performOnServerThread(server, normalised))
                    .get(SERVER_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw RpcException.illegalState("The command '" + normalised + "' did not run within "
                    + SERVER_TIMEOUT_SECONDS + "s: the integrated server is not draining its task "
                    + "queue, so it is busy or stuck.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw RpcException.illegalState("Interrupted while waiting for '" + normalised + "'.");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RpcException rpc) {
                throw rpc;
            }
            throw RpcException.illegalState("The command '" + normalised + "' failed on the server "
                    + "thread: " + cause);
        }
    }

    /**
     * The part that must not run anywhere else: building the source reads the player's position and
     * level, and dispatch mutates the world.
     */
    private static Result performOnServerThread(MinecraftServer server, String normalised) {
        List<String> output = Collections.synchronizedList(new ArrayList<>());
        CommandSource collector = new CommandSource() {
            @Override
            public void sendSystemMessage(Component component) {
                output.add(component.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }

            @Override
            public boolean alwaysAccepts() {
                return true;
            }
        };

        // Run as the player when there is one, so relative coordinates and selectors behave the way
        // they would if the command had been typed into chat.
        CommandSourceStack source = server.createCommandSourceStack().withSource(collector).withMaximumPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER);
        ServerPlayer serverPlayer = serverPlayer(server);
        if (serverPlayer != null) {
            source = source.withEntity(serverPlayer)
                    .withPosition(serverPlayer.position())
                    .withRotation(serverPlayer.getRotationVector())
                    .withLevel(serverPlayer.level());
        }

        // Brigadier reports success through the source's result callback rather than a return
        // value, so this is the only way to learn whether the command actually did anything.
        boolean[] success = { false };
        int[] value = { 0 };
        source = source.withCallback((succeeded, result) -> {
            success[0] = succeeded;
            value[0] = result;
        });

        server.getCommands().performPrefixedCommand(source, normalised);
        // The thread is reported because getting it wrong is invisible until it corrupts
        // something: this ran on the render thread for a long time, and the only evidence was a
        // "[Render thread/ERROR] [minecraft/Commands]" line in a game log after a client had
        // already crashed. Naming it makes the invariant checkable from outside.
        return new Result(success[0], value[0], new ArrayList<>(output), Thread.currentThread().getName());
    }

    /**
     * Runs a block of work as a single server-thread task, with the commands inside it taking
     * {@link #execute}'s same-thread path and running inline.
     *
     * For grouping, not for speed: measured, a world reset varies by a couple of seconds run to run
     * and the hops are lost in that. What it buys is that a sequence lands without the server
     * ticking in between -- which for the determinism setup is the point, since otherwise the world
     * gets to tick a few times while half its rules are still the defaults.
     */
    public static void onServerThread(Runnable work) {
        MinecraftServer server = requireServer();
        if (server.isSameThread()) {
            work.run();
            return;
        }
        try {
            server.submit(work).get(SERVER_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw RpcException.illegalState("The server did not run the requested work within "
                    + SERVER_TIMEOUT_SECONDS + "s, so it is busy or stuck.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw RpcException.illegalState("Interrupted while waiting for the server thread.");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RpcException rpc) {
                throw rpc;
            }
            throw RpcException.illegalState("The server thread failed: " + cause);
        }
    }

    /** How long the client thread will wait for the server to pick the command up. */
    private static final int SERVER_TIMEOUT_SECONDS = 10;

    public static MinecraftServer requireServer() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            throw RpcException.illegalState("There is no integrated server: commands need a singleplayer world. "
                    + "Run 'clientdevbridge world-reset' first.");
        }
        return server;
    }

    private static ServerPlayer serverPlayer(MinecraftServer server) {
        if (Minecraft.getInstance().player == null) {
            return null;
        }
        return server.getPlayerList().getPlayer(Minecraft.getInstance().player.getUUID());
    }

}
