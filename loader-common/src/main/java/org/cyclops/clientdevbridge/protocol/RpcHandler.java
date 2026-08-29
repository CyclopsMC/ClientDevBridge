package org.cyclops.clientdevbridge.protocol;

import com.google.gson.JsonObject;

import java.util.concurrent.CompletableFuture;

/**
 * One JSON-RPC method implementation.
 *
 * Handlers are called on the WebSocket connection thread and return a future so that
 * work needing the render thread can be scheduled without blocking it — see the threading
 * rule in the plan: handlers hop onto the client thread and the connection awaits the result,
 * never the other way round.
 *
 * @author rubensworks
 */
@FunctionalInterface
public interface RpcHandler {

    /**
     * @param params the request's {@code params} object, never null (an absent {@code params} becomes an empty object)
     * @return a future of the {@code result} value; complete with null for methods that return nothing
     */
    CompletableFuture<Object> handle(JsonObject params);

}
