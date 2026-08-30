package org.cyclops.clientdevbridge.mcadapter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.cyclops.clientdevbridge.protocol.RpcException;

/**
 * Opening and closing screens.
 *
 * Opening a block's GUI is a real right-click through {@link MultiPlayerGameMode#useItemOn}, so the
 * block's own interaction logic runs and the resulting screen is the one a player would get — not a
 * screen constructed out of band, which would skip whatever the mod does on interaction.
 *
 * @author rubensworks
 */
public class ScreenControl {

    public static void close() {
        Minecraft.getInstance().setScreen(null);
    }

    /**
     * Right-clicks a block.
     *
     * @param approach when true, teleports the player next to the block and looks at it first.
     *                 The server enforces a reach limit, so without this a distant block simply
     *                 does nothing and the caller is left wondering why.
     */
    public static void openBlock(BlockPos pos, boolean approach) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = ClientState.requirePlayer();
        MultiPlayerGameMode gameMode = minecraft.gameMode;
        if (gameMode == null) {
            throw RpcException.illegalState("There is no game mode yet; the world is still loading.");
        }

        Vec3 center = Vec3.atCenterOf(pos);
        if (approach) {
            approach(pos);
        } else {
            double distance = player.getEyePosition().distanceTo(center);
            double reach = player.blockInteractionRange();
            if (distance > reach) {
                throw RpcException.illegalState(String.format(
                        "The block at %d,%d,%d is %.1f blocks away but the reach limit is %.1f. "
                                + "Teleport closer first, or leave 'approach' enabled.",
                        pos.getX(), pos.getY(), pos.getZ(), distance, reach));
            }
        }

        // Aim at the top face's centre: for the vast majority of blocks any face works, and the
        // top face is the one that is reachable from the standing position chosen by approach().
        BlockHitResult hit = new BlockHitResult(center, Direction.UP, pos, false);
        gameMode.useItemOn(ClientState.requirePlayer(), InteractionHand.MAIN_HAND, hit);
    }

    /**
     * How much closer than the reach limit the player has to already be for the teleport to be
     * skipped. Sitting exactly on the limit would make the interaction depend on rounding.
     */
    private static final double REACH_MARGIN = 0.5d;

    /**
     * Puts the player a couple of blocks away from the target and looks straight at it.
     *
     * When the player can already reach the block, nothing is teleported. That is not just an
     * optimisation: a teleport the player does not need is actively harmful, because the server
     * ignores interactions from a client it is still waiting on a teleport confirmation from. The
     * position hardly changes in that case, so waiting for the player to "arrive" returns
     * immediately, the click goes out during the confirmation window, and the server drops it
     * without a word. It is exactly the state a previous {@code screen.open} leaves the player in,
     * which made every repeated call fail.
     *
     * When it does teleport, the caller must wait for the player to arrive before clicking, for
     * the same underlying reason. {@link #approachTarget(BlockPos)} is what to wait for.
     *
     * @return whether the player was teleported
     */
    public static boolean approach(BlockPos pos) {
        LocalPlayer player = ClientState.requirePlayer();
        Vec3 center = Vec3.atCenterOf(pos);
        PlayerControl.lookAt(center);
        if (player.getEyePosition().distanceTo(center) <= player.blockInteractionRange() - REACH_MARGIN) {
            return false;
        }
        double[] target = approachTarget(pos);
        CommandRunner.run(String.format("tp @s %.2f %.2f %.2f 0 30", target[0], target[1], target[2]));
        PlayerControl.lookAt(center);
        return true;
    }

    /**
     * Where {@link #approach(BlockPos)} puts the player, so the caller can wait for it to land
     * there instead of guessing how long the round trip takes.
     */
    public static double[] approachTarget(BlockPos pos) {
        return new double[] { pos.getX() + 0.5d, pos.getY() + 1.0d, pos.getZ() + 2.5d };
    }

    /**
     * Explains why nothing opened, with the numbers needed to tell the cases apart.
     */
    public static String describeFailedOpen(BlockPos pos) {
        LocalPlayer player = ClientState.requirePlayer();
        double distance = player.getEyePosition().distanceTo(Vec3.atCenterOf(pos));
        String block = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(ClientState.requireLevel().getBlockState(pos).getBlock()).toString();
        String reason = distance > player.blockInteractionRange()
                ? "The player ended up %.1f blocks away, past the %.1f reach limit, so the server "
                        + "rejected the click."
                : "The player is %.1f blocks away, inside the %.1f reach limit, so the click was "
                        + "delivered and the block simply opened nothing.";
        return String.format(
                "Right-clicking %s at %s opened no screen. " + reason
                        + " Blocks without a GUI do nothing here, which is expected; if this one "
                        + "should have opened something, check 'clientdevbridge block %d %d %d'.",
                block, pos.toShortString(), distance, player.blockInteractionRange(),
                pos.getX(), pos.getY(), pos.getZ());
    }

}
