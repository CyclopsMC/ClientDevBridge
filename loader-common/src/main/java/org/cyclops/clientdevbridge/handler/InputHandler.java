package org.cyclops.clientdevbridge.handler;

import com.google.gson.JsonObject;
import org.cyclops.clientdevbridge.mcadapter.ClientState;
import org.cyclops.clientdevbridge.mcadapter.ClientThread;
import org.cyclops.clientdevbridge.mcadapter.Geometry;
import org.cyclops.clientdevbridge.mcadapter.InputControl;
import org.cyclops.clientdevbridge.mcadapter.Keys;
import org.cyclops.clientdevbridge.mcadapter.McAdapter;
import org.cyclops.clientdevbridge.protocol.Dispatcher;
import org.cyclops.clientdevbridge.protocol.Json;
import org.cyclops.clientdevbridge.protocol.Params;
import org.cyclops.clientdevbridge.protocol.RpcException;

/**
 * {@code input.*}: synthetic mouse and keyboard input.
 *
 * Coordinates default to GUI space, which is the space every widget position in a snapshot is
 * reported in — so a caller can feed a snapshot's numbers straight back without converting.
 *
 * @author rubensworks
 */
public class InputHandler {

    public static void register(Dispatcher dispatcher) {
        dispatcher.register("input.mouseMove", raw -> {
            Point point = point(raw, "x", "y");
            return ClientThread.run(() -> InputControl.mouseMove(point.x(), point.y()))
                    .thenApply(ignored -> afterInput());
        });

        dispatcher.register("input.mouseClick", raw -> {
            Params params = new Params(raw);
            Point point = point(raw, "x", "y");
            int button = params.getInt("button", 0);
            if (button < 0 || button > 7) {
                throw RpcException.invalidParams("Parameter 'button' must be 0 (left), 1 (right) or 2 (middle), "
                        + "but was " + button);
            }
            return ClientThread.run(() -> InputControl.mouseClick(point.x(), point.y(), button))
                    .thenApply(ignored -> afterInput());
        });

        dispatcher.register("input.mouseDrag", raw -> {
            Params params = new Params(raw);
            String space = Geometry.requireSpace(params.getString("space", Geometry.SPACE_GUI));
            double[] from = params.getNumberArray("from", 2);
            double[] to = params.getNumberArray("to", 2);
            int button = params.getInt("button", 0);
            int steps = params.getInt("steps", 8);
            return ClientThread.run(() -> InputControl.mouseDrag(
                            Geometry.toGui(from[0], space), Geometry.toGui(from[1], space),
                            Geometry.toGui(to[0], space), Geometry.toGui(to[1], space),
                            button, steps))
                    .thenApply(ignored -> afterInput());
        });

        dispatcher.register("input.scroll", raw -> {
            Params params = new Params(raw);
            Point point = point(raw, "x", "y");
            double deltaX = params.getDouble("dx", 0);
            double deltaY = params.getDouble("dy");
            return ClientThread.run(() -> InputControl.scroll(point.x(), point.y(), deltaX, deltaY))
                    .thenApply(ignored -> afterInput());
        });

        dispatcher.register("input.key", raw -> {
            Params params = new Params(raw);
            int keyCode = Keys.toKeyCode(params.raw().get("key").isJsonPrimitive()
                    && params.raw().get("key").getAsJsonPrimitive().isNumber()
                    ? String.valueOf(params.getInt("key"))
                    : params.getString("key"));
            String action = params.getEnum("action", "tap", "press", "release", "tap");
            int modifiers = params.getInt("modifiers", 0);
            return ClientThread.run(() -> InputControl.key(keyCode, action, modifiers))
                    .thenApply(ignored -> afterInput());
        });

        dispatcher.register("input.type", raw -> {
            String text = new Params(raw).getString("text");
            return ClientThread.run(() -> InputControl.type(text))
                    .thenApply(ignored -> afterInput());
        });

        dispatcher.register("input.hold", raw -> {
            Params params = new Params(raw);
            int keyCode = Keys.toKeyCode(params.getString("key"));
            int ticks = params.getInt("ticks");
            if (ticks < 1 || ticks > WaitHandler.MAX_TICKS) {
                throw RpcException.invalidParams("Parameter 'ticks' must be between 1 and "
                        + WaitHandler.MAX_TICKS + ", but was " + ticks);
            }
            return ClientThread.run(() -> InputControl.setKeyHeld(keyCode, true))
                    .thenCompose(ignored -> McAdapter.tickClock().afterTicks(ticks))
                    .thenCompose(ignored -> ClientThread.run(() -> InputControl.setKeyHeld(keyCode, false)))
                    .thenApply(ignored -> afterInput());
        });
    }

    /**
     * Every input method answers with where the client ended up, so the caller can see what its
     * click did without a second round trip.
     */
    private static JsonObject afterInput() {
        JsonObject result = Json.object();
        result.addProperty("screenClass", ClientState.screenClass());
        result.add("mouse", Json.arrayOfNumbers(InputControl.getMouseX(), InputControl.getMouseY()));
        return result;
    }

    private record Point(double x, double y) {
    }

    private static Point point(JsonObject raw, String xName, String yName) {
        Params params = new Params(raw);
        String space = Geometry.requireSpace(params.getString("space", Geometry.SPACE_GUI));
        Point point = new Point(
                Geometry.toGui(params.getDouble(xName), space),
                Geometry.toGui(params.getDouble(yName), space));
        // A point outside the window is not a click that missed, it is a caller working from stale
        // coordinates -- and silently succeeding at nothing is the worst possible answer, because
        // the next screenshot looks exactly like a click that did nothing.
        int width = Geometry.guiWidth();
        int height = Geometry.guiHeight();
        if (point.x() < 0 || point.y() < 0 || point.x() > width || point.y() > height) {
            throw RpcException.invalidParams(String.format(
                    "Point %.0f,%.0f is outside the %dx%d screen (in %s space). "
                            + "Take a fresh 'clientdevbridge snapshot': the coordinates it prints are "
                            + "in GUI space, and the window may have been resized since the last one.",
                    point.x(), point.y(), width, height, space));
        }
        return point;
    }

}
