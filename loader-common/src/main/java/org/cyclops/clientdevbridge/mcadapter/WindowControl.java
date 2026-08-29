package org.cyclops.clientdevbridge.mcadapter;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.cyclops.clientdevbridge.protocol.RpcException;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;

/**
 * Resizing the window and setting the GUI scale.
 *
 * This is what makes golden-image comparison possible at all: a screenshot is only comparable to
 * one taken earlier if the framebuffer and the GUI scale are the same, and neither is guaranteed
 * by the launcher alone once a window manager gets involved.
 *
 * @author rubensworks
 */
public class WindowControl {

    public static final int MIN_SIZE = 64;
    public static final int MAX_SIZE = 7680;

    /**
     * @param guiScale the fixed GUI scale, or 0 for automatic, or null to leave it alone
     */
    public static void resize(int width, int height, @Nullable Integer guiScale) {
        if (width < MIN_SIZE || height < MIN_SIZE || width > MAX_SIZE || height > MAX_SIZE) {
            throw RpcException.invalidParams("Window size must be between " + MIN_SIZE + " and " + MAX_SIZE
                    + " pixels in each dimension, but was " + width + "x" + height);
        }

        Minecraft minecraft = Minecraft.getInstance();
        Window window = minecraft.getWindow();

        GLFW.glfwSetWindowSize(window.handle(), width, height);
        // GLFW delivers the resize asynchronously; asking Minecraft to re-read it now means the
        // very next frame is already at the new size, rather than one frame later.
        minecraft.resizeGui();

        // The GUI scale has to be validated against the *new* window: the maximum scale is a
        // function of the size, so checking before the resize rejects scales that are about to
        // become perfectly valid.
        if (guiScale != null) {
            int maxScale = window.calculateScale(0, minecraft.isEnforceUnicode());
            if (guiScale < 0 || guiScale > maxScale) {
                throw RpcException.invalidParams("GUI scale must be between 0 (auto) and " + maxScale
                        + " at " + width + "x" + height + ", but was " + guiScale);
            }
            minecraft.options.guiScale().set(guiScale);
            minecraft.resizeGui();
        }
    }

}
