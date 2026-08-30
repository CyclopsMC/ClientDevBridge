package org.cyclops.clientdevbridge.handler;

import com.google.gson.JsonObject;
import org.cyclops.clientdevbridge.mcadapter.ClientState;
import org.cyclops.clientdevbridge.mcadapter.ClientThread;
import net.minecraft.world.InteractionHand;
import org.cyclops.clientdevbridge.mcadapter.Geometry;
import org.cyclops.clientdevbridge.mcadapter.Interaction;
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
            return ClientThread.submit(() -> InputControl.mouseClick(point.x(), point.y(), button))
                    .thenCompose(InputHandler::settle);
        });

        dispatcher.register("player.useItem", raw -> {
            Params params = new Params(raw);
            String hand = params.getEnum("hand", "auto", "auto", "main", "off");
            // A screen opened by the server -- which is every container screen, and so most of the
            // interesting ones -- arrives when the OpenScreen packet does, which is not reliably
            // inside the settle window. Checking once after five ticks reported "no screen opened"
            // for an item that had opened one, which is worse than not offering the wait at all.
            int waitScreenTicks = params.getInt("waitScreenTicks", 0);
            return ClientThread.submit(() -> {
                String held = Interaction.describeHeld(InteractionHand.MAIN_HAND);
                String aimedAt = InputControl.aimedAt();
                InputControl.useItem(switch (hand) {
                    case "main" -> InteractionHand.MAIN_HAND;
                    case "off" -> InteractionHand.OFF_HAND;
                    default -> null;
                });
                return held + "\u0000" + aimedAt;
            }).thenCompose(held -> settleInWorld()
                    .thenCompose(ignored -> waitScreenTicks > 0
                            ? McAdapter.tickClock().awaitCondition(
                                    () -> ClientState.screenClass() != null, waitScreenTicks, null)
                            : java.util.concurrent.CompletableFuture.completedFuture(false))
                    .<Object>thenApply(ignored -> {
                JsonObject result = afterInput();
                String[] parts = held.split("\u0000", 2);
                result.addProperty("held", parts[0]);
                // What the player was looking at when the click went out. On 'auto' a block or an
                // entity takes the click first and the item is never reached, exactly as it would
                // be for a player -- so this is the answer to "why did my item do nothing".
                result.addProperty("aimedAt", parts[1]);
                result.addProperty("hand", hand);
                result.addProperty("screenOpened", ClientState.screenClass() != null);
                return result;
            }));
        });

        dispatcher.register("input.slotClick", raw -> {
            Params params = new Params(raw);
            int button = params.getInt("button", 0);
            if (button < 0 || button > 2) {
                throw RpcException.invalidParams("Parameter 'button' must be 0 (left), 1 (right) or "
                        + "2 (middle), but was " + button);
            }
            String type = params.getEnum("type", "pickup",
                    "pickup", "quick_move", "swap", "clone", "throw", "quick_craft", "pickup_all");
            // Either an index, which is what the snapshot reports and so what a caller usually has,
            // or a point, for one working from a screenshot.
            boolean byPoint = !params.has("slot");
            Point point = byPoint ? point(raw, "x", "y") : null;
            return ClientThread.submit(() -> {
                int slot = byPoint
                        ? InputControl.slotAt(point.x(), point.y())
                        : params.getInt("slot");
                InputControl.slotClick(slot, button, type);
                JsonObject result = afterInput();
                result.addProperty("slot", slot);
                result.addProperty("type", type);
                return result;
            });
        });

        dispatcher.register("input.mouseDrag", raw -> {
            Params params = new Params(raw);
            String space = Geometry.requireSpace(params.getString("space", Geometry.SPACE_GUI));
            double[] from = params.getNumberArray("from", 2);
            double[] to = params.getNumberArray("to", 2);
            int button = params.getInt("button", 0);
            int steps = params.getInt("steps", 8);
            // Both ends go through the same check every other input command uses. This one used to
            // convert the coordinates directly, so a drag to 9999,9999 dropped whatever it was
            // carrying on the floor and reported success.
            Point start = point(from[0], from[1], space);
            Point end = point(to[0], to[1], space);
            return ClientThread.run(() -> InputControl.mouseDrag(
                            start.x(), start.y(), end.x(), end.y(), button, steps))
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
    /**
     * Reports a click, waiting first if it went to the world.
     *
     * A click on a screen is handled there and then, so reading the state straight afterwards is
     * accurate. A click with no screen open is not: it queues a key binding that Minecraft
     * processes in the next tick, and what that does may itself be a server round trip. Reading
     * immediately reported {@code screen: none} at the moment a click opened one -- true when it
     * was measured, wrong by the time anyone saw it, and indistinguishable from the click having
     * done nothing at all.
     */
    private static java.util.concurrent.CompletableFuture<Object> settle(boolean wentToTheWorld) {
        return wentToTheWorld
                ? settleInWorld().thenCompose(ignored -> ClientThread.<Object>submit(InputHandler::afterInput))
                : ClientThread.<Object>submit(InputHandler::afterInput);
    }

    /** The same five ticks {@code world.use} allows, and for the same server round trip. */
    private static java.util.concurrent.CompletableFuture<Long> settleInWorld() {
        return McAdapter.tickClock().afterTicks(5);
    }

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
        return point(params.getDouble(xName), params.getDouble(yName), space);
    }

    /** Converts a caller's point into GUI space, refusing one that is not on screen. */
    private static Point point(double x, double y, String space) {
        Geometry.requireOnScreen(x, y, space);
        return new Point(Geometry.toGui(x, space), Geometry.toGui(y, space));
    }

}
