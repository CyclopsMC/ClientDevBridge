package org.cyclops.clientdevbridge.mcadapter;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import org.cyclops.clientdevbridge.protocol.Json;
import org.cyclops.clientdevbridge.protocol.RpcException;

import javax.annotation.Nullable;

/**
 * Read-only views of the client's current state.
 *
 * Everything here reaches into {@code net.minecraft} and is therefore expected to need
 * attention when porting to a new Minecraft version; handlers only ever see the JSON it produces.
 *
 * @author rubensworks
 */
public class ClientState {

    /**
     * The OpenGL renderer string, e.g. {@code llvmpipe (LLVM 20.1.2, 256 bits)}.
     */
    public static String glRenderer() {
        try {
            return com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo().name();
        } catch (Throwable e) {
            // Only reachable off the render thread or before the context exists; not worth failing over.
            return "unknown";
        }
    }

    /**
     * The running Minecraft version, e.g. {@code 1.21.1}.
     */
    public static String minecraftVersion() {
        return net.minecraft.SharedConstants.getCurrentVersion().name();
    }

    /**
     * The registry access the client currently has, needed to serialise {@code Component}s.
     * Falls back to the built-in registries before a world is joined.
     */
    public static net.minecraft.core.HolderLookup.Provider registryAccess() {
        net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
        return level == null
                ? net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(
                        net.minecraft.core.registries.BuiltInRegistries.REGISTRY)
                : level.registryAccess();
    }

    @Nullable
    public static Screen screen() {
        return Minecraft.getInstance().gui.screen();
    }

    @Nullable
    public static String screenClass() {
        Screen screen = screen();
        return screen == null ? null : screen.getClass().getName();
    }

    /**
     * Whether the client has finished starting up.
     *
     * The bridge's socket opens during mod initialisation, which is well before the game is
     * usable: the resource reload is still running behind a {@code LoadingOverlay}, no client
     * ticks are happening, and anything asked of the game will either race or hang. This is the
     * signal that the client is genuinely ready to be driven.
     */
    public static boolean isLoaded() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.gui.overlay() == null && screen() != null || inWorld();
    }

    public static boolean inWorld() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level != null && minecraft.player != null;
    }

    /**
     * @return the player, failing with a clear message when there is no world loaded
     */
    public static LocalPlayer requirePlayer() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            throw RpcException.illegalState(
                    "Not in a world. Run 'clientdevbridge world-reset' or 'world-load <name>' first.");
        }
        return player;
    }

    public static ClientLevel requireLevel() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            throw RpcException.illegalState(
                    "Not in a world. Run 'clientdevbridge world-reset' or 'world-load <name>' first.");
        }
        return level;
    }

    public static Screen requireScreen() {
        Screen screen = screen();
        if (screen == null) {
            throw RpcException.illegalState("No screen is open. Open one with 'open-gui' or press a key first.");
        }
        return screen;
    }

    /**
     * Whether the chunk containing a position is loaded on the client.
     */
    public static boolean isChunkLoaded(int x, int y, int z) {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level != null && minecraft.level.isLoaded(new net.minecraft.core.BlockPos(x, y, z));
    }

    /**
     * A class loader that can see Minecraft's own dependencies, which is where the {@code eval}
     * escape hatch has to look for a script engine.
     */
    public static ClassLoader vanillaClassLoader() {
        return Minecraft.class.getClassLoader();
    }

    /**
     * Whether a world is not merely joined but ready to be looked at: the integrated server is up,
     * the loading screen is gone, and the given position is in a loaded chunk.
     *
     * All three matter to a screenshot. A client that has joined but is still showing "Loading
     * terrain" renders something, and it is not the world.
     */
    public static boolean isWorldReadyAt(int x, int y, int z) {
        Minecraft minecraft = Minecraft.getInstance();
        return inWorld()
                && minecraft.getSingleplayerServer() != null
                && screen() == null
                && minecraft.level != null
                && minecraft.level.isLoaded(new net.minecraft.core.BlockPos(x, y, z));
    }

    /**
     * The vanilla objects the {@code eval} escape hatch exposes to a script.
     *
     * They live here rather than in the handler because they are vanilla types, and every one of
     * them has been reached through a different accessor at some point in Minecraft's history.
     */
    public static java.util.Map<String, Object> scriptBindings() {
        Minecraft minecraft = Minecraft.getInstance();
        java.util.Map<String, Object> bindings = new java.util.LinkedHashMap<>();
        bindings.put("mc", minecraft);
        bindings.put("player", minecraft.player);
        bindings.put("level", minecraft.level);
        bindings.put("screen", screen());
        bindings.put("window", minecraft.getWindow());
        bindings.put("server", minecraft.getSingleplayerServer());
        return bindings;
    }

    /**
     * The {@code status} result: cheap enough to poll constantly.
     */
    public static JsonObject status(long tick) {
        Minecraft minecraft = Minecraft.getInstance();
        JsonObject status = Json.object();
        status.addProperty("loaded", isLoaded());
        status.addProperty("inWorld", inWorld());
        status.addProperty("screenClass", screenClass());
        status.addProperty("tick", tick);
        status.addProperty("fps", minecraft.getFps());
        // The run directory the game actually chose. Which one that is depends on the Gradle
        // plugin, not on the loader, so the CLI cannot know it up front: it pins the determinism
        // options into its best guess before launch and corrects itself against this afterwards.
        status.addProperty("gameDir", minecraft.gameDirectory.toPath().toAbsolutePath().normalize().toString());

        ClientLevel level = minecraft.level;
        status.addProperty("dimension", level == null ? null : level.dimension().identifier().toString());

        LocalPlayer player = minecraft.player;
        if (player == null) {
            status.add("player", com.google.gson.JsonNull.INSTANCE);
        } else {
            JsonObject playerObject = Json.object();
            playerObject.add("pos", Json.arrayOfNumbers(player.getX(), player.getY(), player.getZ()));
            playerObject.addProperty("yaw", player.getYRot());
            playerObject.addProperty("pitch", player.getXRot());
            status.add("player", playerObject);
        }

        // The renderer name is what keys golden-image sets: llvmpipe and a real GPU do not
        // produce identical pixels, and one tolerance cannot cover both without hiding regressions.
        status.addProperty("glRenderer", glRenderer());

        Geometry.addMetrics(status);
        return status;
    }

}
