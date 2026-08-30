package org.cyclops.clientdevbridge.mcadapter;

import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.ContainerInput;
import org.cyclops.clientdevbridge.protocol.RpcException;

/**
 * The one version-sensitive step of a slot click: which enum names the operation, and which method
 * on the game mode performs it.
 *
 * Both moved in Minecraft 26 -- {@code ClickType} became {@code ContainerInput} and
 * {@code handleInventoryMouseClick} became {@code handleContainerInput} -- while everything around
 * the call did not. Keeping the pair here rather than in {@link InputControl} means the branches
 * differ in one small file instead of in the middle of a large shared one.
 *
 * @author rubensworks
 */
public class SlotInput {

    /**
     * Performs a click on a slot, given the protocol's name for what kind of click it is.
     *
     * The names are the protocol's, not the enum's: a caller should never have to know a Java
     * constant, and mapping them explicitly is also what keeps a rename from reaching the wire.
     */
    public static void perform(int containerId, int slotId, int button, String type) {
        Minecraft.getInstance().gameMode.handleContainerInput(
                containerId, slotId, button, clickType(type), ClientState.requirePlayer());
    }

    private static ContainerInput clickType(String name) {
        return switch (name) {
            case "pickup" -> ContainerInput.PICKUP;
            case "quick_move" -> ContainerInput.QUICK_MOVE;
            case "swap" -> ContainerInput.SWAP;
            case "clone" -> ContainerInput.CLONE;
            case "throw" -> ContainerInput.THROW;
            case "quick_craft" -> ContainerInput.QUICK_CRAFT;
            case "pickup_all" -> ContainerInput.PICKUP_ALL;
            default -> throw RpcException.invalidParams("Unknown click type '" + name + "'.");
        };
    }

}
