package org.cyclops.clientdevbridge.mcadapter;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
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

    /** The last synthetic mouse position, so drags and clicks agree on where the pointer is. */
    private static double mouseX;
    private static double mouseY;

    public static double getMouseX() {
        return mouseX;
    }

    public static double getMouseY() {
        return mouseY;
    }

    public static void mouseMove(double x, double y) {
        mouseX = x;
        mouseY = y;

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

    public static void mouseClick(double x, double y, int button) {
        Screen screen = ClientState.screen();
        if (screen == null) {
            // No screen: a click is an in-world attack (0) or use (1).
            KeyMapping mapping = button == 0
                    ? Minecraft.getInstance().options.keyAttack
                    : Minecraft.getInstance().options.keyUse;
            KeyMapping.click(mapping.getDefaultKey());
            return;
        }
        mouseMove(x, y);
        screen.mouseClicked(x, y, button);
        screen.mouseReleased(x, y, button);
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
        Screen screen = requireScreenFor("scrolling");
        mouseMove(x, y);
        screen.mouseScrolled(x, y, deltaX, deltaY);
    }

    /**
     * @param action one of {@code press}, {@code release} or {@code tap}
     */
    public static void key(int keyCode, String action, int modifiers) {
        Screen screen = ClientState.screen();
        if (screen != null) {
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

        KeyMapping mapping = Keys.findMapping(keyCode);
        if (mapping == null) {
            throw RpcException.illegalState("No screen is open and no key binding matches key code " + keyCode
                    + ", so there is nothing to deliver the key press to.");
        }
        InputConstants.Key key = mapping.getDefaultKey();
        switch (action) {
            case "press" -> KeyMapping.set(key, true);
            case "release" -> KeyMapping.set(key, false);
            default -> KeyMapping.click(key);
        }
    }

    /**
     * Holds a key down. Release is the caller's job, after the ticks it wants have elapsed.
     */
    public static void setKeyHeld(int keyCode, boolean held) {
        KeyMapping mapping = Keys.findMapping(keyCode);
        if (mapping == null) {
            throw RpcException.illegalState("No key binding matches key code " + keyCode
                    + ", so it cannot be held. Held keys are for movement and other bound actions.");
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
