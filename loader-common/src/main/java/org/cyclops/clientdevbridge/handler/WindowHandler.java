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
 * @author rubensworks
 */
public class WindowHandler {

    public static void register(Dispatcher dispatcher) {
        dispatcher.register("window.resize", raw -> {
            Params params = new Params(raw);
            int width = params.getInt("width");
            int height = params.getInt("height");
            Integer guiScale = params.has("guiScale") ? params.getInt("guiScale") : null;

            return ClientThread.run(() -> WindowControl.resize(width, height, guiScale))
                    // A couple of ticks so the resize has actually taken effect before the metrics
                    // in the result are read; otherwise they describe the old window.
                    .thenCompose(ignored -> McAdapter.tickClock().afterTicks(2))
                    .thenCompose(ignored -> ClientThread.submit(() -> {
                        JsonObject result = Json.object();
                        Geometry.addMetrics(result);
                        return result;
                    }));
        });
    }

}
