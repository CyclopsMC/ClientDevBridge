package org.cyclops.clientdevbridge.mcadapter;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.InteractionHand;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import com.mojang.blaze3d.platform.Window;
import org.cyclops.clientdevbridge.protocol.RpcException;

import javax.annotation.Nullable;

/**
 * Delivers synthetic input.
 *
 * Input goes to the open {@link Screen} through the same {@code GuiEventListener} methods GLFW
 * callbacks use, so widgets see exactly what a real click would produce. With no screen open the
 * input is in-world instead, and has to go through {@link KeyMapping} — there is no listener to
 * deliver a raw key event to.
 *
 * All coordinates arriving here are already in GUI space; {@link Geometry} does the conversion.
 *
 * @author rubensworks
 */
public class InputControl {

    /**
     * Where the pointer is, in GUI space, read from the game rather than remembered.
     *
     * This used to return the last position the bridge itself asked for, defaulting to 0,0. On a
     * virtual display the real pointer starts at the centre of the window, so before any
     * mouse-move a snapshot reported "mouse at 0,0" and, in the same breath, marked the centre
     * slot hovered -- two true-looking statements that contradict each other, and the screenshot
     * sided with the game.
     */
    public static double getMouseX() {
        Window window = Minecraft.getInstance().getWindow();
        return Minecraft.getInstance().mouseHandler.xpos
                * window.getGuiScaledWidth() / (double) window.getScreenWidth();
    }

    public static double getMouseY() {
        Window window = Minecraft.getInstance().getWindow();
        return Minecraft.getInstance().mouseHandler.ypos
                * window.getGuiScaledHeight() / (double) window.getScreenHeight();
    }

    public static void mouseMove(double x, double y) {
        // Minecraft recomputes hover state every frame from MouseHandler's own position, so it has
        // to be written directly. Asking GLFW to move the cursor does not work: it is ignored while
        // the window is not focused, which it never is under a virtual display. Without this the
        // hovered slot, the hover highlight and any rendered tooltip keep tracking wherever the
        // pointer physically is, and a screenshot shows that rather than what was asked for.
        Minecraft minecraft = Minecraft.getInstance();
        Window window = minecraft.getWindow();
        minecraft.mouseHandler.xpos = x * (double) window.getScreenWidth() / window.getGuiScaledWidth();
        minecraft.mouseHandler.ypos = y * (double) window.getScreenHeight() / window.getGuiScaledHeight();

        Screen screen = ClientState.screen();
        if (screen != null) {
            screen.mouseMoved(x, y);
        }
    }

    /**
     * @return whether the click went to the world rather than to a screen, which the caller needs
     *         because the two settle at different times -- see {@link #useItem}
     */
    public static boolean mouseClick(double x, double y, int button) {
        Screen screen = ClientState.screen();
        if (screen == null) {
            // No screen: a click is an in-world attack (0) or use (1).
            KeyMapping mapping = button == 0
                    ? Minecraft.getInstance().options.keyAttack
                    : Minecraft.getInstance().options.keyUse;
            KeyMapping.click(mapping.getDefaultKey());
            return true;
        }
        mouseMove(x, y);
        screen.mouseClicked(x, y, button);
        screen.mouseReleased(x, y, button);
        return false;
    }

    /**
     * Uses the held item on nothing -- the right-click that opens a book, drinks a potion or, for a
     * great many mods, opens the item's own screen.
     *
     * The plainest interaction in the game and the one thing that had no command: everything else
     * here takes a block position, so a mod whose entry point is an item rather than a block could
     * not be reached at all except by knowing that an in-world {@code mouseClick} falls through to
     * this key binding.
     *
     * The binding is <em>queued</em>, not performed: Minecraft processes it in the next tick's
     * {@code handleKeybinds}, and whatever it does then may itself be a server round trip. So this
     * returns having started something, and the caller waits.
     */
    /**
     * What the player is aimed at, which decides what a right-click does.
     *
     * A use aimed at a block interacts with the block and the held item is never reached -- correct,
     * and the single most confusing way for {@link #useItem} to appear not to work. Reporting it
     * turns "nothing happened" into "you were looking at a chest".
     */
    public static String aimedAt() {
        net.minecraft.world.phys.HitResult hit = Minecraft.getInstance().hitResult;
        return hit == null ? "none" : hit.getType().name().toLowerCase(java.util.Locale.ROOT);
    }

    public static void useItem(@Nullable InteractionHand hand) {
        Minecraft minecraft = Minecraft.getInstance();
        if (hand == null) {
            // The key binding, which is what a player presses. It decides for itself whether the
            // click is a block interaction, an entity interaction or an item use, and which hand
            // to use -- and getting that decision for free is the whole reason to go through it.
            KeyMapping.click(minecraft.options.keyUse.getDefaultKey());
            return;
        }
        // A named hand is not something a player can ask for: the binding tries the main hand and
        // falls through to the off hand only if the first does nothing. This is the call it makes
        // once it has chosen, so a caller testing an off-hand item can reach it directly -- at the
        // cost of skipping the block-or-item decision, which is why it is not the default.
        minecraft.gameMode.useItem(ClientState.requirePlayer(), hand);
    }

    /**
     * Clicks a container slot with an explicit operation -- the shift-click that a plain
     * click cannot express.
     *
     * A screen decides what a click means before it acts: {@code AbstractContainerScreen.mouseClicked}
     * works out which operation the button and the modifiers meant, and then calls
     * {@code slotClicked}. The modifiers it reads come from the static {@code Screen.hasShiftDown()},
     * which asks GLFW for the real keyboard state -- so no amount of synthetic input reaches it, and
     * {@code mouseClicked} takes no modifiers to pass either. That whole route is closed.
     *
     * What is open is saying which operation was meant. {@code quick_move} <em>is</em> shift-click;
     * naming it skips a guess rather than faking the input the guess is made from.
     *
     * The one thing this does not do is run a screen's own {@code slotClicked} override, and there
     * is no way to: it is {@code protected}. A mod that filters slot moves there would be bypassed.
     * Nothing found so far does, and the alternative -- a mixin on {@code hasShiftDown} -- buys that
     * case with the first injection point in a mod that has none.
     */
    public static void slotClick(int slotId, int button, String type) {
        AbstractContainerScreen<?> screen = requireContainerScreen();
        AbstractContainerMenu menu = screen.getMenu();
        if (slotId < 0 || slotId >= menu.slots.size()) {
            throw RpcException.invalidParams(String.format(
                    "Parameter 'slot' must be one of this screen's %d slots (0-%d), but was %d. "
                            + "'clientdevbridge snapshot --json' lists them with their indices.",
                    menu.slots.size(), menu.slots.size() - 1, slotId));
        }

        // The pointer goes to the slot first. Nothing in the click needs it, but the screen renders
        // its hover highlight from the real pointer position, so a screenshot taken afterwards would
        // otherwise show the highlight somewhere else entirely.
        Slot slot = menu.slots.get(slotId);
        mouseMove(screen.leftPos + slot.x + 8, screen.topPos + slot.y + 8);

        // Which enum names the operation, and which method performs it, both moved in Minecraft
        // 26; SlotInput is where that difference lives.
        SlotInput.perform(menu.containerId, slotId, button, type);
    }

    /**
    /** The slot whose rectangle contains a GUI-space point, for callers that have a click, not an index. */
    public static int slotAt(double x, double y) {
        AbstractContainerScreen<?> screen = requireContainerScreen();
        for (Slot slot : screen.getMenu().slots) {
            double left = screen.leftPos + slot.x;
            double top = screen.topPos + slot.y;
            if (x >= left && x < left + 16 && y >= top && y < top + 16) {
                return slot.index;
            }
        }
        throw RpcException.invalidParams(String.format(
                "No slot covers %.0f,%.0f on %s. 'clientdevbridge snapshot --json' lists every slot "
                        + "with its position; pass the index instead of a point.",
                x, y, screen.getClass().getSimpleName()));
    }

    private static AbstractContainerScreen<?> requireContainerScreen() {
        Screen screen = ClientState.screen();
        if (!(screen instanceof AbstractContainerScreen<?> container)) {
            throw RpcException.illegalState(screen == null
                    ? "No screen is open, so there are no slots to click."
                    : screen.getClass().getSimpleName() + " has no slots; only a container screen does.");
        }
        return container;
    }

    public static void mouseDrag(double fromX, double fromY, double toX, double toY, int button, int steps) {
        Screen screen = requireScreenFor("dragging");
        if (steps < 1) {
            throw RpcException.invalidParams("Parameter 'steps' must be at least 1, but was " + steps);
        }
        mouseMove(fromX, fromY);
        screen.mouseClicked(fromX, fromY, button);

        double previousX = fromX;
        double previousY = fromY;
        for (int step = 1; step <= steps; step++) {
            double progress = (double) step / steps;
            double x = fromX + (toX - fromX) * progress;
            double y = fromY + (toY - fromY) * progress;
            screen.mouseDragged(x, y, button, x - previousX, y - previousY);
            previousX = x;
            previousY = y;
        }

        mouseMove(toX, toY);
        screen.mouseReleased(toX, toY, button);
    }

    public static void scroll(double x, double y, double deltaX, double deltaY) {
        Screen screen = ClientState.screen();
        // With no screen open, scrolling is how a player changes hotbar slot -- refusing it was
        // refusing the only thing scrolling does in the world.
        if (screen == null) {
            PlayerControl.scrollHotbar(deltaY);
            return;
        }
        mouseMove(x, y);
        screen.mouseScrolled(x, y, deltaX, deltaY);
    }

    /**
     * @param action one of {@code press}, {@code release} or {@code tap}
     */
    public static void key(InputConstants.Key key, String action, int modifiers) {
        Screen screen = ClientState.screen();
        // A screen takes keyboard events by code. A mouse binding has no code to give it, so those
        // go down the binding path even with a screen open -- they would otherwise be delivered as
        // whichever keyboard key happens to share the button's number.
        if (screen != null && key.getType() == InputConstants.Type.KEYSYM) {
            int keyCode = key.getValue();
            switch (action) {
                case "press" -> screen.keyPressed(keyCode, -1, modifiers);
                case "release" -> screen.keyReleased(keyCode, -1, modifiers);
                default -> {
                    screen.keyPressed(keyCode, -1, modifiers);
                    screen.keyReleased(keyCode, -1, modifiers);
                }
            }
            return;
        }

        KeyMapping mapping = Keys.findMapping(key);
        if (mapping == null) {
            throw RpcException.illegalState("No screen is open and no key binding matches "
                    + Keys.describe(key) + ", so there is nothing to deliver the key press to. "
                    + "In-world input has to go through a binding: name the action ('ATTACK', 'USE', "
                    + "'HOTBAR_3') rather than the key it happens to sit on.");
        }
        switch (action) {
            case "press" -> KeyMapping.set(mapping.getDefaultKey(), true);
            case "release" -> KeyMapping.set(mapping.getDefaultKey(), false);
            default -> KeyMapping.click(mapping.getDefaultKey());
        }
    }

    /**
     * Holds a key down. Release is the caller's job, after the ticks it wants have elapsed.
     */
    public static void setKeyHeld(InputConstants.Key key, boolean held) {
        KeyMapping mapping = Keys.findMapping(key);
        if (mapping == null) {
            throw RpcException.illegalState("No key binding matches " + Keys.describe(key)
                    + ", so it cannot be held. Held input is for movement, attack, use and other "
                    + "bound actions -- try 'ATTACK', 'USE' or a movement key.");
        }
        KeyMapping.set(mapping.getDefaultKey(), held);
    }

    public static void type(String text) {
        Screen screen = requireScreenFor("typing");
        text.codePoints().forEach(codePoint -> {
            for (char character : Character.toChars(codePoint)) {
                screen.charTyped(character, 0);
            }
        });
    }

    private static Screen requireScreenFor(String what) {
        Screen screen = ClientState.screen();
        if (screen == null) {
            throw RpcException.illegalState("No screen is open, so there is nothing to deliver " + what + " to. "
                    + "Open one with 'open-gui' first.");
        }
        return screen;
    }

    /**
     * Releases every held key. Called when a world is left, so a held movement key cannot leak
     * into the next scenario.
     */
    public static void releaseAll() {
        KeyMapping.releaseAll();
    }

    @Nullable
    static Screen currentScreen() {
        return ClientState.screen();
    }

}
