package org.cyclops.clientdevbridge.mcadapter;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.cyclops.clientdevbridge.protocol.RpcException;
import org.lwjgl.glfw.GLFW;

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

    public static void applySize(int width, int height) {
        if (width < MIN_SIZE || height < MIN_SIZE || width > MAX_SIZE || height > MAX_SIZE) {
            throw RpcException.invalidParams("Window size must be between " + MIN_SIZE + " and " + MAX_SIZE
                    + " pixels in each dimension, but was " + width + "x" + height);
        }

        Minecraft minecraft = Minecraft.getInstance();
        GLFW.glfwSetWindowSize(minecraft.getWindow().handle(), width, height);
        // GLFW delivers the resize asynchronously; asking Minecraft to re-read it now means the
        // very next frame is already at the new size, rather than one frame later. It does not
        // mean the window reports the new size yet -- see hasResized.
        minecraft.resizeGui();
    }

    /**
     * Whether the window has stopped reporting the size it had before {@link #applySize}.
     *
     * The caller has to wait for this before doing anything that depends on the new size. GLFW's
     * own size callback has not run when applySize returns, so the window still reports the old
     * dimensions, and it is an edge rather than an exact match that is waited for: the framebuffer
     * is not required to end up exactly the requested size, and on a scaled display it will not.
     */
    public static boolean hasResized(int previousWidth, int previousHeight) {
        Window window = Minecraft.getInstance().getWindow();
        return window.getScreenWidth() != previousWidth || window.getScreenHeight() != previousHeight;
    }

    public static int[] screenSize() {
        Window window = Minecraft.getInstance().getWindow();
        return new int[] { window.getScreenWidth(), window.getScreenHeight() };
    }

    /**
     * @param guiScale the fixed GUI scale, or 0 for automatic
     */
    public static void applyGuiScale(int guiScale) {
        Minecraft minecraft = Minecraft.getInstance();
        Window window = minecraft.getWindow();
        // Validated against the window as it is now, which is why the caller has to have waited:
        // the maximum scale is a function of the size, and checking against the old size both
        // rejects scales that just became valid and quotes a limit for a window that is gone.
        int maxScale = window.calculateScale(0, minecraft.isEnforceUnicode());
        if (guiScale < 0 || guiScale > maxScale) {
            throw RpcException.invalidParams("GUI scale must be between 0 (auto) and " + maxScale
                    + " at " + window.getScreenWidth() + "x" + window.getScreenHeight()
                    + ", but was " + guiScale);
        }
        minecraft.options.guiScale().set(guiScale);
        minecraft.resizeGui();
    }

}
