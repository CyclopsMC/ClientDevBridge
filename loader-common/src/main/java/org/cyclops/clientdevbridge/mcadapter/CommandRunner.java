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
    public record Result(boolean success, int value, List<String> output) {
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
        CommandSourceStack source = server.createCommandSourceStack().withSource(collector).withMaximumPermission(4);
        ServerPlayer serverPlayer = serverPlayer(server);
        if (serverPlayer != null) {
            source = source.withEntity(serverPlayer)
                    .withPosition(serverPlayer.position())
                    .withRotation(serverPlayer.getRotationVector())
                    .withLevel(serverPlayer.serverLevel());
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
        return new Result(success[0], value[0], new ArrayList<>(output));
    }

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
