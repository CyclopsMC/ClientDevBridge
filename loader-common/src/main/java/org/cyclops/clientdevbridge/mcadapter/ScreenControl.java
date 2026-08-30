package org.cyclops.clientdevbridge.mcadapter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;

/**
 * Opening and closing screens.
 *
 * Opening a block's GUI is a real right-click, so the block's own interaction logic runs and the
 * resulting screen is the one a player would get -- not a screen constructed out of band, which
 * would skip whatever the mod does on interaction. {@link Interaction} does the clicking; what is
 * left here is the screen itself, and explaining a click that opened nothing.
 *
 * @author rubensworks
 */
public class ScreenControl {

    public static void close() {
        Minecraft.getInstance().setScreen(null);
    }

    public static void openBlock(Aim aim) {
        Interaction.useOn(aim, InteractionHand.MAIN_HAND);
    }

    /**
     * Explains why nothing opened, with the numbers needed to tell the cases apart.
     *
     * The aim is named whenever one was given, because on a multipart block it is the likeliest
     * thing to have been wrong: the click landed, and it landed somewhere else on the block.
     */
    public static String describeFailedOpen(Aim aim) {
        LocalPlayer player = ClientState.requirePlayer();
        double distance = player.getEyePosition().distanceTo(aim.point());
        String block = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(ClientState.requireLevel().getBlockState(aim.pos()).getBlock()).toString();
        if (distance > player.blockInteractionRange()) {
            return String.format(
                    "Right-clicking %s at %s opened no screen. The player ended up %.1f blocks "
                            + "away, past the %.1f reach limit, so the server rejected the click.",
                    block, aim.pos().toShortString(), distance, player.blockInteractionRange());
        }
        return String.format(
                "Right-clicking %s at %s (aimed at the %s side) opened no screen. The player is "
                        + "%.1f blocks away, inside the %.1f reach limit, so the click was "
                        + "delivered and nothing opened. Either the block has no GUI, or it has "
                        + "one per side -- a cable's parts, say -- and this side has none. "
                        + "'clientdevbridge block %d %d %d --nbt' shows what is actually there; "
                        + "--face picks a different side.",
                block, aim.pos().toShortString(), aim.face().getName(), distance,
                player.blockInteractionRange(), aim.pos().getX(), aim.pos().getY(), aim.pos().getZ());
    }

}
