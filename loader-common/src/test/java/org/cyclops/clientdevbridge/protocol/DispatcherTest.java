package org.cyclops.clientdevbridge.protocol;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JSON-RPC layer knows nothing about Minecraft, so it can be tested outright.
 *
 * @author rubensworks
 */
class DispatcherTest {

    private static JsonObject dispatch(Dispatcher dispatcher, String request) {
        String response = dispatcher.dispatch(request).join();
        return response == null ? null : JsonParser.parseString(response).getAsJsonObject();
    }

    private static Dispatcher withEcho() {
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.register("echo", params -> {
            JsonObject result = new JsonObject();
            result.addProperty("said", new Params(params).getString("text"));
            return CompletableFuture.completedFuture(result);
        });
        return dispatcher;
    }

    @Test
    void answersARequestWithItsId() {
        JsonObject response = dispatch(withEcho(),
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"echo\",\"params\":{\"text\":\"hi\"}}");
        assertEquals(7, response.get("id").getAsInt());
        assertEquals("2.0", response.get("jsonrpc").getAsString());
        assertEquals("hi", response.getAsJsonObject("result").get("said").getAsString());
        assertFalse(response.has("error"));
    }

    @Test
    void reportsAnUnknownMethodAndListsTheKnownOnes() {
        JsonObject response = dispatch(withEcho(), "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"nope\"}");
        JsonObject error = response.getAsJsonObject("error");
        assertEquals(RpcErrorCodes.METHOD_NOT_FOUND, error.get("code").getAsInt());
        assertTrue(error.get("message").getAsString().contains("echo"),
                "the error should list the methods that do exist");
    }

    @Test
    void reportsMalformedJsonAsAParseError() {
        JsonObject response = dispatch(withEcho(), "{not json");
        assertEquals(RpcErrorCodes.PARSE_ERROR, response.getAsJsonObject("error").get("code").getAsInt());
        assertTrue(response.get("id").isJsonNull());
    }

    @Test
    void rejectsPositionalParameters() {
        JsonObject response = dispatch(withEcho(),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"echo\",\"params\":[1,2]}");
        assertEquals(RpcErrorCodes.INVALID_PARAMS, response.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void turnsAnRpcExceptionIntoItsOwnCode() {
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.register("boom", params -> {
            throw RpcException.illegalState("no world");
        });
        JsonObject error = dispatch(dispatcher, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"boom\"}")
                .getAsJsonObject("error");
        assertEquals(RpcErrorCodes.ILLEGAL_STATE, error.get("code").getAsInt());
        assertEquals("no world", error.get("message").getAsString());
    }

    @Test
    void unwrapsAnRpcExceptionFromAFailedFuture() {
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.register("boom", params ->
                CompletableFuture.failedFuture(RpcException.invalidParams("bad")));
        JsonObject error = dispatch(dispatcher, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"boom\"}")
                .getAsJsonObject("error");
        assertEquals(RpcErrorCodes.INVALID_PARAMS, error.get("code").getAsInt());
        assertEquals("bad", error.get("message").getAsString());
    }

    @Test
    void reportsAnUnexpectedFailureAsAnInternalError() {
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.register("boom", params -> {
            throw new IllegalArgumentException("oops");
        });
        JsonObject error = dispatch(dispatcher, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"boom\"}")
                .getAsJsonObject("error");
        assertEquals(RpcErrorCodes.INTERNAL_ERROR, error.get("code").getAsInt());
        assertTrue(error.get("message").getAsString().contains("oops"));
    }

    @Test
    void answersNothingForANotification() {
        assertNull(dispatch(withEcho(), "{\"jsonrpc\":\"2.0\",\"method\":\"echo\",\"params\":{\"text\":\"hi\"}}"));
    }

    @Test
    void treatsAnAbsentParamsAsEmpty() {
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.register("count", params ->
                CompletableFuture.completedFuture(params.size()));
        JsonObject response = dispatch(dispatcher, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"count\"}");
        assertEquals(0, response.get("result").getAsInt());
    }

    @Test
    void rendersNotificationsWithNoId() {
        JsonObject notification = JsonParser
                .parseString(Dispatcher.notification("screen.changed", new JsonObject()))
                .getAsJsonObject();
        assertEquals("screen.changed", notification.get("method").getAsString());
        assertFalse(notification.has("id"), "a notification must not carry an id");
    }

    @Test
    void refusesToRegisterTheSameMethodTwice() {
        Dispatcher dispatcher = withEcho();
        try {
            dispatcher.register("echo", params -> CompletableFuture.completedFuture(null));
            org.junit.jupiter.api.Assertions.fail("expected a duplicate registration to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("echo"));
        }
    }

}
