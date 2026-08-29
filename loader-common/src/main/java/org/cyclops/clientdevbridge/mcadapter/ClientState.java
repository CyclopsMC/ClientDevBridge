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
     * The running Minecraft version, e.g. {@code 1.21.1}.
     */
    public static String minecraftVersion() {
        return net.minecraft.SharedConstants.getCurrentVersion().getName();
    }

    @Nullable
    public static Screen screen() {
        return Minecraft.getInstance().screen;
    }

    @Nullable
    public static String screenClass() {
        Screen screen = screen();
        return screen == null ? null : screen.getClass().getName();
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
     * The {@code status} result: cheap enough to poll constantly.
     */
    public static JsonObject status(long tick) {
        Minecraft minecraft = Minecraft.getInstance();
        JsonObject status = Json.object();
        status.addProperty("inWorld", inWorld());
        status.addProperty("screenClass", screenClass());
        status.addProperty("tick", tick);
        status.addProperty("fps", minecraft.getFps());

        ClientLevel level = minecraft.level;
        status.addProperty("dimension", level == null ? null : level.dimension().location().toString());

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

        Geometry.addMetrics(status);
        return status;
    }

}
