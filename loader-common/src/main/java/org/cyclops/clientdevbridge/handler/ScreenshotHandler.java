package org.cyclops.clientdevbridge.handler;

import com.google.gson.JsonObject;
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
 * Everything about how a frame is actually read out of the GPU lives behind
 * {@link FrameCapture#capture}, so this handler compiles unchanged on every branch.
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

            return ready
                    // The metrics are read on the client thread before capturing, so they describe
                    // the same frame the caller is about to receive.
                    .thenCompose(ignored -> ClientThread.submit(() -> new Request(
                            resolveRegion(regionObject), scale, Geometry.metrics())))
                    .thenCompose(request -> FrameCapture.capture(request.region(), request.scale())
                            .thenApply(png -> render(png, request.metrics(), request.region())));
        });
    }

    private static JsonObject render(FrameCapture.Png png, JsonObject metrics,
                                     @Nullable FrameCapture.Region region) {
        JsonObject result = Json.object();
        result.addProperty("png", Base64.getEncoder().encodeToString(png.bytes()));
        result.addProperty("width", png.width());
        result.addProperty("height", png.height());
        result.addProperty("bytes", png.bytes().length);
        for (String key : metrics.keySet()) {
            result.add(key, metrics.get(key));
        }
        // The rectangle that was actually captured, echoed in both spaces. A caller who asked in
        // gui space gets a pixel-sized image back, so without this there is no way to tell a region
        // that landed where it was meant to from one that was off by a gui scale factor.
        if (region != null) {
            result.add("region", Json.arrayOfNumbers(region.x(), region.y(), region.width(), region.height()));
            double guiScale = metrics.get("guiScale").getAsDouble();
            result.add("regionGui", Json.arrayOfNumbers(region.x() / guiScale, region.y() / guiScale,
                    region.width() / guiScale, region.height() / guiScale));
        }
        return result;
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

    private record Request(@Nullable FrameCapture.Region region, @Nullable Double scale, JsonObject metrics) {
    }

}
