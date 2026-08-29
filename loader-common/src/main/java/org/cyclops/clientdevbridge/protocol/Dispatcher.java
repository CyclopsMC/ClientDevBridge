package org.cyclops.clientdevbridge.protocol;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.cyclops.clientdevbridge.ClientDevBridge;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Maps JSON-RPC 2.0 method names onto {@link RpcHandler}s and renders the responses.
 *
 * This class is entirely version-independent: it knows nothing about Minecraft. Everything
 * Minecraft-shaped lives behind the handlers, and everything version-shaped behind
 * {@code mcadapter}.
 *
 * @author rubensworks
 */
public class Dispatcher {

    private final Map<String, RpcHandler> handlers = new LinkedHashMap<>();

    public Dispatcher register(String method, RpcHandler handler) {
        if (this.handlers.put(method, handler) != null) {
            throw new IllegalArgumentException("Duplicate handler registered for method " + method);
        }
        return this;
    }

    public Set<String> getMethods() {
        return Collections.unmodifiableSet(this.handlers.keySet());
    }

    /**
     * Parses and dispatches one request message.
     *
     * @param message the raw text frame
     * @return a future of the response text, or of null when the message was a notification
     *         (no {@code id}) and therefore needs no reply
     */
    public CompletableFuture<String> dispatch(String message) {
        JsonObject request;
        try {
            JsonElement parsed = JsonParser.parseString(message);
            if (!parsed.isJsonObject()) {
                return CompletableFuture.completedFuture(
                        errorResponse(null, RpcErrorCodes.INVALID_REQUEST, "A request must be a JSON object", null));
            }
            request = parsed.getAsJsonObject();
        } catch (JsonParseException e) {
            return CompletableFuture.completedFuture(
                    errorResponse(null, RpcErrorCodes.PARSE_ERROR, "Malformed JSON: " + e.getMessage(), null));
        }

        JsonElement id = request.get("id");
        boolean isNotification = id == null || id.isJsonNull();

        if (!request.has("method") || !request.get("method").isJsonPrimitive()) {
            return CompletableFuture.completedFuture(isNotification ? null
                    : errorResponse(id, RpcErrorCodes.INVALID_REQUEST, "A request must have a string 'method'", null));
        }
        String method = request.get("method").getAsString();

        JsonObject params = new JsonObject();
        if (request.has("params") && !request.get("params").isJsonNull()) {
            JsonElement rawParams = request.get("params");
            if (!rawParams.isJsonObject()) {
                return CompletableFuture.completedFuture(isNotification ? null : errorResponse(id,
                        RpcErrorCodes.INVALID_PARAMS,
                        "'params' must be an object; positional parameters are not supported", null));
            }
            params = rawParams.getAsJsonObject();
        }

        RpcHandler handler = this.handlers.get(method);
        if (handler == null) {
            return CompletableFuture.completedFuture(isNotification ? null
                    : errorResponse(id, RpcErrorCodes.METHOD_NOT_FOUND,
                            "Unknown method '" + method + "'. Known methods: " + String.join(", ", getMethods()), null));
        }

        CompletableFuture<Object> result;
        try {
            result = handler.handle(params);
        } catch (Throwable e) {
            result = CompletableFuture.failedFuture(e);
        }

        return result
                .handle((value, throwable) -> {
                    if (isNotification) {
                        if (throwable != null) {
                            ClientDevBridge.LOGGER.warn("Notification '{}' failed", method, throwable);
                        }
                        return null;
                    }
                    if (throwable != null) {
                        return renderThrowable(id, method, throwable);
                    }
                    JsonObject response = new JsonObject();
                    response.addProperty("jsonrpc", "2.0");
                    response.add("id", id);
                    response.add("result", value == null ? new JsonObject() : Json.toTree(value));
                    return Json.GSON.toJson(response);
                });
    }

    private String renderThrowable(JsonElement id, String method, Throwable throwable) {
        Throwable cause = throwable;
        while ((cause instanceof CompletionException || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof RpcException rpcException) {
            return errorResponse(id, rpcException.getCode(), rpcException.getMessage(), rpcException.getData());
        }
        ClientDevBridge.LOGGER.error("Handler for '{}' threw", method, cause);
        String message = cause.getMessage() == null ? cause.getClass().getName()
                : cause.getClass().getSimpleName() + ": " + cause.getMessage();
        return errorResponse(id, RpcErrorCodes.INTERNAL_ERROR, message, null);
    }

    /**
     * Renders a JSON-RPC error response. Public so the connection layer can report transport-level failures.
     */
    public static String errorResponse(@Nullable JsonElement id, int code, String message, @Nullable JsonElement data) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        if (data != null) {
            error.add("data", data);
        }
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        if (id == null) {
            response.add("id", com.google.gson.JsonNull.INSTANCE);
        } else {
            response.add("id", id);
        }
        response.add("error", error);
        return Json.GSON.toJson(response);
    }

    /**
     * Renders a server-to-client notification, which carries no {@code id} and expects no reply.
     */
    public static String notification(String method, @Nullable JsonObject params) {
        JsonObject message = new JsonObject();
        message.addProperty("jsonrpc", "2.0");
        message.addProperty("method", method);
        message.add("params", params == null ? new JsonObject() : params);
        return Json.GSON.toJson(message);
    }

}
