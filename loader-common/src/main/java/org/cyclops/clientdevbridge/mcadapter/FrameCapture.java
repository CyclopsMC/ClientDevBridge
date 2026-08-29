package org.cyclops.clientdevbridge.mcadapter;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.cyclops.clientdevbridge.protocol.RpcException;

import javax.annotation.Nullable;
import java.io.IOException;

/**
 * Reads the main render target into a PNG.
 *
 * The capture itself must happen on the render thread while the GL context is current;
 * PNG encoding is pure CPU work on the resulting off-heap buffer, so it is done by the caller's
 * thread afterwards — the same split vanilla's own screenshot code uses.
 *
 * @author rubensworks
 */
public class FrameCapture {

    /**
     * A rectangle in pixel space.
     */
    public record Region(int x, int y, int width, int height) {
    }

    /**
     * Grabs the current framebuffer. Must be called on the render thread.
     */
    public static NativeImage grab() {
        RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
        return Screenshot.takeScreenshot(target);
    }

    /**
     * Crops and/or rescales a captured image, closing the source when a new image is produced.
     *
     * @param source the freshly grabbed frame
     * @param region the pixel-space rectangle to keep, or null for the whole frame
     * @param scale  a multiplier applied to the output size, or null for 1:1
     */
    public static NativeImage transform(NativeImage source, @Nullable Region region, @Nullable Double scale) {
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();

        int x = region == null ? 0 : region.x();
        int y = region == null ? 0 : region.y();
        int width = region == null ? sourceWidth : region.width();
        int height = region == null ? sourceHeight : region.height();

        if (width <= 0 || height <= 0) {
            source.close();
            throw RpcException.invalidParams("Region width and height must both be positive, but were "
                    + width + "x" + height);
        }
        if (x < 0 || y < 0 || x + width > sourceWidth || y + height > sourceHeight) {
            source.close();
            throw RpcException.invalidParams("Region " + x + "," + y + " " + width + "x" + height
                    + " does not fit inside the " + sourceWidth + "x" + sourceHeight + " framebuffer");
        }

        double effectiveScale = scale == null ? 1.0d : scale;
        if (effectiveScale <= 0 || effectiveScale > 8) {
            source.close();
            throw RpcException.invalidParams("Parameter 'scale' must be greater than 0 and at most 8, but was "
                    + effectiveScale);
        }

        int outputWidth = Math.max(1, (int) Math.round(width * effectiveScale));
        int outputHeight = Math.max(1, (int) Math.round(height * effectiveScale));

        boolean unchanged = x == 0 && y == 0 && width == sourceWidth && height == sourceHeight
                && outputWidth == sourceWidth && outputHeight == sourceHeight;
        if (unchanged) {
            return source;
        }

        NativeImage result = new NativeImage(outputWidth, outputHeight, false);
        try {
            source.resizeSubRectTo(x, y, width, height, result);
        } catch (Throwable e) {
            result.close();
            throw e;
        } finally {
            source.close();
        }
        return result;
    }

    /**
     * Encodes to PNG bytes and releases the image. Safe to call off the render thread.
     */
    public static byte[] encodeAndClose(NativeImage image) {
        try {
            return image.asByteArray();
        } catch (IOException e) {
            throw new RpcException(org.cyclops.clientdevbridge.protocol.RpcErrorCodes.INTERNAL_ERROR,
                    "Failed to PNG-encode the captured frame: " + e.getMessage());
        } finally {
            image.close();
        }
    }

}
