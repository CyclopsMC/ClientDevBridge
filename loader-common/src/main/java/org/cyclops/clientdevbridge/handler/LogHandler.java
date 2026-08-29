package org.cyclops.clientdevbridge.handler;

import com.google.gson.JsonObject;
import org.cyclops.clientdevbridge.logging.LogRing;
import org.cyclops.clientdevbridge.protocol.Dispatcher;
import org.cyclops.clientdevbridge.protocol.Json;
import org.cyclops.clientdevbridge.protocol.Params;
import org.cyclops.clientdevbridge.protocol.RpcException;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * {@code log.tail}: the game's own recent log lines, filtered by severity and by regex.
 *
 * The default severity is INFO, because a dev client emits hundreds of TRACE lines a second and an
 * unfiltered tail would be nothing else.
 *
 * Runs entirely off the client thread, so it still answers while the game is busy or hung —
 * which is exactly when it is most useful.
 *
 * @author rubensworks
 */
public class LogHandler {

    public static final int DEFAULT_LINES = 200;
    public static final int MAX_LINES = 5000;

    public static void register(Dispatcher dispatcher, LogRing ring) {
        dispatcher.register("log.tail", raw -> {
            Params params = new Params(raw);
            int lines = params.getInt("lines", DEFAULT_LINES);
            if (lines <= 0) {
                throw RpcException.invalidParams("Parameter 'lines' must be positive, but was " + lines);
            }
            String filter = params.getString("filter", null);
            LogRing.Level minLevel = LogRing.requireLevel(params.getString("level", null));

            List<String> tail = ring.tail(Math.min(lines, MAX_LINES), filter, minLevel);
            JsonObject result = Json.object();
            result.add("lines", Json.arrayOfStrings(tail));
            result.addProperty("level", minLevel.name().toLowerCase(java.util.Locale.ROOT));
            result.addProperty("buffered", ring.size());
            return CompletableFuture.completedFuture(result);
        });
    }

}
