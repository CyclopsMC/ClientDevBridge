package org.cyclops.clientdevbridge.handler;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import org.cyclops.clientdevbridge.mcadapter.ClientThread;
import org.cyclops.clientdevbridge.mcadapter.FrameCapture;
import org.cyclops.clientdevbridge.mcadapter.Geometry;
import org.cyclops.clientdevbridge.mcadapter.McAdapter;
import org.cyclops.clientdevbridge.protocol.Dispatcher;
import org.cyclops.clientdevbridge.protocol.Json;
import org.cyclops.clientdevbridge.protocol.Params;
import org.cyclops.clientdevbridge.protocol.RpcException;

import javax.annotation.Nullable;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

/**
 * {@code screenshot}: the current framebuffer as a base64 PNG.
 *
 * The CLI writes the bytes to a file and prints its path; base64 never reaches a terminal.
 *
 * @author rubensworks
 */
public class ScreenshotHandler {

    public static void register(Dispatcher dispatcher) {
        dispatcher.register("screenshot", raw -> {
            Params params = new Params(raw);
            int afterTicks = params.getInt("afterTicks", 0);
            if (afterTicks < 0 || afterTicks > WaitHandler.MAX_TICKS) {
                throw RpcException.invalidParams("Parameter 'afterTicks' must be between 0 and "
                        + WaitHandler.MAX_TICKS + ", but was " + afterTicks);
            }
            JsonObject regionObject = params.getObject("region");
            Double scale = params.has("scale") ? params.getDouble("scale") : null;

            CompletableFuture<?> ready = afterTicks > 0
                    ? McAdapter.tickClock().afterTicks(afterTicks)
                    : CompletableFuture.completedFuture(null);

            return ready.thenCompose(ignored -> capture(regionObject, scale));
        });
    }

    private static CompletableFuture<Object> capture(@Nullable JsonObject regionObject, @Nullable Double scale) {
        // Hop a frame first, so the buffer we read was rendered after the request arrived.
        return ClientThread.<Capture>submitAfterFrame(() -> {
            FrameCapture.Region region = resolveRegion(regionObject);
            JsonObject metrics = Geometry.metrics();
            NativeImage image = FrameCapture.transform(FrameCapture.grab(), region, scale);
            return new Capture(image, metrics);
        }).thenApply(capture -> {
            // PNG encoding is CPU work on an off-heap buffer, so it happens off the render thread.
            int width = capture.image().getWidth();
            int height = capture.image().getHeight();
            byte[] png = FrameCapture.encodeAndClose(capture.image());

            JsonObject result = Json.object();
            result.addProperty("png", Base64.getEncoder().encodeToString(png));
            result.addProperty("width", width);
            result.addProperty("height", height);
            result.addProperty("bytes", png.length);
            for (String key : capture.metrics().keySet()) {
                result.add(key, capture.metrics().get(key));
            }
            return result;
        });
    }

    @Nullable
    private static FrameCapture.Region resolveRegion(@Nullable JsonObject regionObject) {
        if (regionObject == null) {
            return null;
        }
        Params region = new Params(regionObject);
        String space = Geometry.requireSpace(region.getString("space", Geometry.SPACE_GUI));
        int x = (int) Math.round(Geometry.toPixel(region.getDouble("x"), space));
        int y = (int) Math.round(Geometry.toPixel(region.getDouble("y"), space));
        int width = (int) Math.round(Geometry.toPixel(region.getDouble("w"), space));
        int height = (int) Math.round(Geometry.toPixel(region.getDouble("h"), space));
        return new FrameCapture.Region(x, y, width, height);
    }

    private record Capture(NativeImage image, JsonObject metrics) {
    }

}
