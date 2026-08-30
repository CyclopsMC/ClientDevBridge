package org.cyclops.clientdevbridge.mcadapter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import org.cyclops.clientdevbridge.protocol.RpcException;

/**
 * Right-clicking a block, the way a player does.
 *
 * The click goes through {@link MultiPlayerGameMode#useItemOn}, so the block's own interaction
 * logic runs and whatever it does -- opening a screen, placing a part, consuming an item -- is
 * exactly what a player would get. Nothing is constructed out of band.
 *
 * @author rubensworks
 */
public class Interaction {

    /**
     * How much closer than the reach limit the player has to already be for the teleport to be
     * skipped. Sitting exactly on the limit would make the interaction depend on rounding.
     */
    private static final double REACH_MARGIN = 0.5d;

    /** How long to wait for the server to have the rotation the click depends on. */
    private static final int ROTATION_TIMEOUT_TICKS = 20;

    public static InteractionResult useOn(Aim aim, InteractionHand hand) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = ClientState.requirePlayer();
        MultiPlayerGameMode gameMode = minecraft.gameMode;
        if (gameMode == null) {
            throw RpcException.illegalState("There is no game mode yet; the world is still loading.");
        }
        double distance = player.getEyePosition().distanceTo(aim.point());
        double reach = player.blockInteractionRange();
        if (distance > reach) {
            throw RpcException.illegalState(String.format(
                    "The aimed point on the block at %s is %.1f blocks away but the reach limit is "
                            + "%.1f. Teleport closer first, or leave 'approach' enabled.",
                    aim.pos().toShortString(), distance, reach));
        }
        return gameMode.useItemOn(player, hand, aim.hit());
    }

    /**
     * Puts the player where the aim wants them and points them at it.
     *
     * When the player can already reach the block, nothing is teleported. That is not just an
     * optimisation: a teleport the player does not need is actively harmful, because the server
     * ignores interactions from a client it is still waiting on a teleport confirmation from. The
     * position hardly changes in that case, so waiting for the player to "arrive" returns
     * immediately, the click goes out during the confirmation window, and the server drops it
     * without a word.
     *
     * A directed aim always teleports, even from inside reach. Standing anywhere that can see the
     * block is enough for a vanilla block, but a multipart block resolves the click by casting a
     * ray from the eye, so the eye has to be on the right side of the face -- which the current
     * position generally is not.
     *
     * @return whether the player was teleported
     */
    public static boolean approach(Aim aim) {
        LocalPlayer player = ClientState.requirePlayer();
        if (!aim.isDirected()
                && player.getEyePosition().distanceTo(aim.point()) <= player.blockInteractionRange() - REACH_MARGIN) {
            aim(aim);
            return false;
        }
        double[] target = aim.standingPosition();
        float[] angles = aim.lookAngles();
        // The rotation goes into the teleport rather than being applied afterwards. /tp is what
        // makes the server's copy of the player authoritative, and a rotation applied on the
        // client during the teleport's round trip is computed from the position the player has
        // not left yet -- which aims at the block from wherever they were standing before.
        CommandRunner.run(String.format("tp @s %.3f %.3f %.3f %.2f %.2f",
                target[0], target[1], target[2], angles[0], angles[1]));
        return true;
    }

    /**
     * Points the player at the aim from wherever they are now.
     *
     * Called once the player has arrived, so {@link PlayerControl#lookAt} measures from the
     * position they actually ended up in -- the teleport puts them close to the target, not
     * exactly on it, because the server drops them onto the ground.
     */
    public static void aim(Aim aim) {
        PlayerControl.lookAt(aim.point());
    }

    /**
     * Whether the server's copy of the player is looking where the client is.
     *
     * A multipart block re-raytraces from the <em>server</em> player, and rotation only reaches the
     * server on the next movement packet -- so aiming and clicking in the same tick evaluates the
     * click against the previous rotation. Waiting for the server to agree is the same discipline
     * {@code approach} already applies to position, for the same underlying reason.
     *
     * A client with no integrated server cannot check, and reports true rather than blocking
     * forever; the caller's fixed wait covers that case.
     */
    public static boolean serverSeesRotation() {
        LocalPlayer player = ClientState.requirePlayer();
        ServerPlayer serverPlayer = serverPlayer();
        if (serverPlayer == null) {
            return true;
        }
        return Math.abs(net.minecraft.util.Mth.degreesDifference(serverPlayer.getYRot(), player.getYRot())) < 0.5f
                && Math.abs(serverPlayer.getXRot() - player.getXRot()) < 0.5f;
    }

    public static int rotationTimeoutTicks() {
        return ROTATION_TIMEOUT_TICKS;
    }

    /**
     * Holds or releases sneak, which some blocks read to pick a different interaction -- a wrench
     * removing a part rather than configuring it, for one.
     *
     * The server learns about it from its own packet, so a caller has to let a tick pass before
     * the click, exactly as with rotation.
     */
    public static void setSneaking(boolean sneaking) {
        LocalPlayer player = ClientState.requirePlayer();
        player.setShiftKeyDown(sneaking);
        // The input is an immutable record here, so the flag is set by rebuilding it rather than
        // by assignment. It is what the client sends the server, which is where sneak has to
        // arrive for a block to read it.
        net.minecraft.world.entity.player.Input keys = player.input.keyPresses;
        player.input.keyPresses = new net.minecraft.world.entity.player.Input(
                keys.forward(), keys.backward(), keys.left(), keys.right(), keys.jump(),
                sneaking, keys.sprint());
    }

    public static boolean isSneaking() {
        return ClientState.requirePlayer().isShiftKeyDown();
    }

    /** What the player is holding, so a caller can see whether the click consumed anything. */
    public static String describeHeld(InteractionHand hand) {
        ItemStack stack = ClientState.requirePlayer().getItemInHand(hand);
        return stack.isEmpty()
                ? "empty"
                : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem())
                        + " x" + stack.getCount();
    }

    public static InteractionHand hand(String name) {
        return "off".equals(name) ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    }

    private static ServerPlayer serverPlayer() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            return null;
        }
        return server.getPlayerList().getPlayer(ClientState.requirePlayer().getUUID());
    }

    /** Where {@link #approach(Aim)} puts the player, so the caller can wait for it to land there. */
    public static double[] approachTarget(Aim aim) {
        return aim.standingPosition();
    }

    private Interaction() {
    }

}
