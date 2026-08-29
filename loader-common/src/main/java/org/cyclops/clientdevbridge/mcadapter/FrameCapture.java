package org.cyclops.clientdevbridge.mcadapter;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.cyclops.clientdevbridge.protocol.RpcException;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

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
     * The whole capture pipeline: grab the framebuffer, crop or rescale it, and PNG-encode it.
     *
     * This is a single adapter entry point on purpose. How a frame is read out of the GPU is one
     * of the most version-churning things in Minecraft — it has been a synchronous call, and it
     * has been a callback driven by a GPU buffer copy — so the handler must never see that shape.
     * It sees a future, on every branch.
     *
     * @param region the pixel-space rectangle to keep, or null for the whole frame
     * @param scale  a multiplier applied to the output size, or null for 1:1
     */
    public static CompletableFuture<Png> capture(@Nullable Region region, @Nullable Double scale) {
        // Hop a frame first, so the buffer read was rendered after the request arrived.
        return ClientThread.<CompletableFuture<NativeImage>>submitAfterFrame(FrameCapture::grab)
                .thenCompose(grabbed -> grabbed)
                // Cropping and PNG encoding are CPU work on an off-heap buffer, so they happen
                // off the render thread.
                .thenApply(raw -> {
                    NativeImage image = transform(raw, region, scale);
                    int width = image.getWidth();
                    int height = image.getHeight();
                    return new Png(encodeAndClose(image), width, height);
                });
    }

    /**
     * An encoded frame.
     */
    public record Png(byte[] bytes, int width, int height) {
    }

    /**
     * Starts a framebuffer read. Must be called on the render thread.
     *
     * The read is asynchronous here: it is a texture-to-buffer copy whose callback fires once the
     * GPU command encoder has run it, so the image is not available when this returns.
     */
    static CompletableFuture<NativeImage> grab() {
        RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        CompletableFuture<NativeImage> future = new CompletableFuture<>();
        Screenshot.takeScreenshot(target, future::complete);
        return future;
    }

    /**
     * Crops and/or rescales a captured image, closing the source when a new image is produced.
     *
     * @param source the freshly grabbed frame
     * @param region the pixel-space rectangle to keep, or null for the whole frame
     * @param scale  a multiplier applied to the output size, or null for 1:1
     */
    static NativeImage transform(NativeImage source, @Nullable Region region, @Nullable Double scale) {
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
    static byte[] encodeAndClose(NativeImage image) {
        // NativeImage no longer exposes its PNG encoder as a byte array; writeToFile is the only
        // public route to it, so the encode goes through a temporary file.
        Path file = null;
        try {
            file = Files.createTempFile("clientdevbridge-frame", ".png");
            image.writeToFile(file);
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new RpcException(org.cyclops.clientdevbridge.protocol.RpcErrorCodes.INTERNAL_ERROR,
                    "Failed to PNG-encode the captured frame: " + e.getMessage());
        } finally {
            image.close();
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // A leftover temp file is not worth failing a screenshot over.
                }
            }
        }
    }

}
