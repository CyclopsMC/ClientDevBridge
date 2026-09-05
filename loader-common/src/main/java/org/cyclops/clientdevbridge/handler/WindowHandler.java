package org.cyclops.clientdevbridge.handler;

import com.google.gson.JsonObject;
import org.cyclops.clientdevbridge.mcadapter.ClientThread;
import org.cyclops.clientdevbridge.mcadapter.Geometry;
import org.cyclops.clientdevbridge.mcadapter.McAdapter;
import org.cyclops.clientdevbridge.mcadapter.WindowControl;
import org.cyclops.clientdevbridge.protocol.Dispatcher;
import org.cyclops.clientdevbridge.protocol.Json;
import org.cyclops.clientdevbridge.protocol.Params;

/**
 * {@code window.resize}: pin the framebuffer size and GUI scale, for reproducible screenshots.
 *
 * The size is in framebuffer pixels, not in the screen coordinates GLFW sizes a window in; see
 * {@link WindowControl#applySize}, which is where the two part company on a scaled display.
 *
 * @author rubensworks
 */
public class WindowHandler {

    /** How long to give GLFW to deliver a window resize before giving up on it. */
    private static final int RESIZE_TIMEOUT_TICKS = 40;

    public static void register(Dispatcher dispatcher) {
        dispatcher.register("window.resize", raw -> {
            Params params = new Params(raw);
            int width = params.getInt("width");
            int height = params.getInt("height");
            Integer guiScale = params.has("guiScale") ? params.getInt("guiScale") : null;

            // Resizing and setting the GUI scale cannot happen in one go. GLFW delivers the new
            // window size asynchronously, so immediately after the resize the window still reports
            // the old one -- and the maximum GUI scale is computed from that size. Doing both at
            // once therefore validated the scale against the window that was being replaced: going
            // from 640x360 to 854x480 at scale 2 was rejected as "must be between 0 and 1", after
            // the resize itself had already been applied, so the command both worked and failed.
            return ClientThread.submit(() -> {
                        int[] before = WindowControl.pixelSize();
                        WindowControl.applySize(width, height);
                        return before;
                    })
                    // Compared in framebuffer pixels, the space the request is in. Comparing the
                    // window's screen size against it took "already 854x480" for granted on a
                    // display that scales windows, where a 854x480 window is a 1708x960
                    // framebuffer, and skipped the wait for a resize that was about to happen.
                    .thenCompose(before -> before[0] == width && before[1] == height
                            // Already the requested size, so there is no edge to wait for.
                            ? java.util.concurrent.CompletableFuture.completedFuture(null)
                            : McAdapter.tickClock().awaitCondition(
                                    () -> WindowControl.hasResized(before[0], before[1]),
                                    RESIZE_TIMEOUT_TICKS, null))
                    .thenCompose(ignored -> guiScale == null
                            ? java.util.concurrent.CompletableFuture.completedFuture(null)
                            : ClientThread.run(() -> WindowControl.applyGuiScale(guiScale)))
                    // A couple of ticks so the scale change has taken effect before the metrics in
                    // the result are read; otherwise they describe the old window.
                    .thenCompose(ignored -> McAdapter.tickClock().afterTicks(2))
                    .thenCompose(ignored -> ClientThread.submit(() -> {
                        JsonObject result = Json.object();
                        Geometry.addMetrics(result);
                        return result;
                    }));
        });
    }

}
