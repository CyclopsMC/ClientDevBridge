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

    /** How many times to press escape before giving up and closing the screen outright. */
    private static final int MAX_ESCAPES = 5;

    /**
     * Closes the open screen the way a player does: by pressing escape.
     *
     * Setting the screen to null directly looks equivalent and is not. A screen gets to handle
     * escape, and many do something on the way out that matters:
     *
     * <ul>
     *   <li>A container screen sends the close packet, so the <em>server</em> stops thinking the
     *       container is open. Closing by fiat leaves it open there.</li>
     *   <li>Integrated Dynamics' aspect settings screen saves the edited value on escape, and
     *       nowhere else. Closing by fiat silently discarded whatever had just been typed.</li>
     *   <li>A sub-screen may go back to its parent rather than close, which is what the player
     *       would see and so what the caller should get.</li>
     * </ul>
     *
     * Escape is pressed until no screen is left, because a screen that goes back to its parent has
     * not finished the job. A screen that ignores escape entirely -- there are a few -- is closed
     * outright rather than looping forever.
     */
    public static void close() {
        Minecraft minecraft = Minecraft.getInstance();
        for (int attempt = 0; attempt < MAX_ESCAPES && ClientState.screen() != null; attempt++) {
            net.minecraft.client.gui.screens.Screen before = ClientState.screen();
            // Through InputControl rather than the screen directly: how a key event is built is
            // version-sensitive, and that is already solved in one place.
            InputControl.key(Keys.toInputKey(ESCAPE_KEY), "tap", 0);
            if (ClientState.screen() == before) {
                // It did not act on escape, so nothing more will come of pressing it again.
                break;
            }
        }
        if (ClientState.screen() != null) {
            minecraft.setScreenAndShow(null);
        }
    }

    private static final int ESCAPE_KEY = 256;

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
