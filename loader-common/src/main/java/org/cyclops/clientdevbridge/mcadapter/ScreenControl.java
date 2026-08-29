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
     * Puts the player a couple of blocks away from the target and looks straight at it.
     *
     * The caller must let a few ticks pass before clicking: the teleport goes through the
     * integrated server, and until it has come back the server still believes the player is
     * wherever it was, and silently rejects the interaction as out of reach.
     */
    public static void approach(BlockPos pos) {
        double x = pos.getX() + 0.5d;
        double y = pos.getY() + 1.0d;
        double z = pos.getZ() + 2.5d;
        CommandRunner.run(String.format("tp @s %.2f %.2f %.2f 0 30", x, y, z));
        PlayerControl.lookAt(Vec3.atCenterOf(pos));
    }

    /**
     * Explains why nothing opened, with the numbers needed to tell the cases apart.
     */
    public static String describeFailedOpen(BlockPos pos) {
        LocalPlayer player = ClientState.requirePlayer();
        double distance = player.getEyePosition().distanceTo(Vec3.atCenterOf(pos));
        String block = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(ClientState.requireLevel().getBlockState(pos).getBlock()).toString();
        return String.format(
                "Right-clicking %s at %s opened no screen. The player is %.1f blocks away "
                        + "(reach %.1f). If that block has no GUI this is expected; otherwise check "
                        + "'clientdevbridge block %d %d %d' and try again with more ticks.",
                block, pos.toShortString(), distance, player.blockInteractionRange(),
                pos.getX(), pos.getY(), pos.getZ());
    }

}
