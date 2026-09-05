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

    /**
     * Resizes the window so that its framebuffer is {@code width} x {@code height} pixels.
     *
     * Framebuffer pixels are what every size in the bridge means: screenshots are in them, pixel
     * space is them, and GUI space is them divided by the scale. GLFW does not take them. It sizes
     * a window in screen coordinates, and on a display that scales windows -- every Retina Mac,
     * and Windows at anything but 100% -- there are more pixels behind a screen coordinate than
     * one. Passing the request straight through therefore worked only where the two agree: on a
     * doubling display it produced a 1708x960 framebuffer for a request of 854x480, and with it a
     * GUI space, screenshots and pixel coordinates all twice the size the same command produces
     * anywhere else.
     *
     * The window's own two sizes give the factor, and they give the real one -- the content scale
     * GLFW reports is what the display asked for, which is not always what the framebuffer ended
     * up at. A display scaling by a fraction can still land a pixel away from the request, because
     * the screen coordinate that maps onto it is not a whole number. The caller is told the size
     * that was reached rather than the one it asked for; the metrics in the result are read from
     * the window afterwards for exactly that reason.
     */
    public static void applySize(int width, int height) {
        if (width < MIN_SIZE || height < MIN_SIZE || width > MAX_SIZE || height > MAX_SIZE) {
            throw RpcException.invalidParams("Window size must be between " + MIN_SIZE + " and " + MAX_SIZE
                    + " pixels in each dimension, but was " + width + "x" + height);
        }

        Minecraft minecraft = Minecraft.getInstance();
        // The DPI conversion from 1.21, with 26's accessor: Window.getWindow() is handle() here.
        Window window = minecraft.getWindow();
        GLFW.glfwSetWindowSize(window.handle(),
                screenCoordinates(width, window.getWidth(), window.getScreenWidth()),
                screenCoordinates(height, window.getHeight(), window.getScreenHeight()));
        // GLFW delivers the resize asynchronously; asking Minecraft to re-read it now means the
        // very next frame is already at the new size, rather than one frame later. It does not
        // mean the window reports the new size yet -- see hasResized.
        minecraft.resizeGui();
    }

    /**
     * Converts a framebuffer pixel count into the screen coordinates GLFW sizes a window in.
     *
     * @param pixels the framebuffer size being asked for
     * @param currentPixels the framebuffer size now
     * @param currentCoordinates the window size now, in screen coordinates
     */
    private static int screenCoordinates(int pixels, int currentPixels, int currentCoordinates) {
        // A window that has not been mapped yet reports zeroes, and one pixel per coordinate is the
        // right guess when there is nothing to measure: it is what an unscaled display does.
        if (currentPixels <= 0 || currentCoordinates <= 0) {
            return pixels;
        }
        return Math.max(1, Math.round(pixels * (float) currentCoordinates / currentPixels));
    }

    /**
     * Whether the framebuffer has stopped reporting the size it had before {@link #applySize}.
     *
     * The caller has to wait for this before doing anything that depends on the new size. GLFW's
     * own size callback has not run when applySize returns, so the window still reports the old
     * dimensions, and it is an edge rather than an exact match that is waited for: on a display
     * that scales by a fraction the framebuffer can settle a pixel away from the request, and
     * waiting for a size it will never report is waiting for the timeout.
     */
    public static boolean hasResized(int previousWidth, int previousHeight) {
        Window window = Minecraft.getInstance().getWindow();
        return window.getWidth() != previousWidth || window.getHeight() != previousHeight;
    }

    /** The framebuffer size, which is the space every size in the protocol is expressed in. */
    public static int[] pixelSize() {
        Window window = Minecraft.getInstance().getWindow();
        return new int[] { window.getWidth(), window.getHeight() };
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
            // In framebuffer pixels, because that is what calculateScale divides: quoting the
            // window's screen size would name a limit that does not follow from the number beside
            // it on a display that scales windows.
            throw RpcException.invalidParams("GUI scale must be between 0 (auto) and " + maxScale
                    + " at " + window.getWidth() + "x" + window.getHeight()
                    + ", but was " + guiScale);
        }
        minecraft.options.guiScale().set(guiScale);
        minecraft.resizeGui();
    }

}
