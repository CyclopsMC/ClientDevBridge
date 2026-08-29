package org.cyclops.clientdevbridge.handler;

import org.cyclops.clientdevbridge.mcadapter.ClientThread;
import org.cyclops.clientdevbridge.mcadapter.Geometry;
import org.cyclops.clientdevbridge.mcadapter.TooltipCapture;
import org.cyclops.clientdevbridge.mcadapter.WidgetWalker;
import org.cyclops.clientdevbridge.protocol.Dispatcher;
import org.cyclops.clientdevbridge.protocol.Params;
import org.cyclops.clientdevbridge.protocol.RpcException;

/**
 * {@code screen.snapshot} and {@code screen.tooltip}: the structured view of what is on screen.
 *
 * @author rubensworks
 */
public class SnapshotHandler {

    public static void register(Dispatcher dispatcher) {
        dispatcher.register("screen.snapshot", raw -> {
            Params params = new Params(raw);
            boolean includeHidden = params.getBoolean("includeHidden", false);
            int maxDepth = params.getInt("maxDepth", WidgetWalker.MAX_DEPTH);
            if (maxDepth < 1) {
                throw RpcException.invalidParams("Parameter 'maxDepth' must be at least 1, but was " + maxDepth);
            }
            return ClientThread.submit(() -> WidgetWalker.snapshot(includeHidden, maxDepth));
        });

        dispatcher.register("screen.tooltip", raw -> {
            Params params = new Params(raw);
            String space = Geometry.requireSpace(params.getString("space", Geometry.SPACE_GUI));
            return ClientThread.submit(() -> TooltipCapture.at(
                    Geometry.toGui(params.getDouble("x"), space),
                    Geometry.toGui(params.getDouble("y"), space)));
        });
    }

}
