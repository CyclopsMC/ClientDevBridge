package org.cyclops.clientdevbridge.protocol;

import com.google.gson.JsonElement;

import javax.annotation.Nullable;

/**
 * A failure that should be reported to the CLI as a JSON-RPC error object.
 *
 * @author rubensworks
 */
public class RpcException extends RuntimeException {

    private final int code;
    @Nullable
    private final JsonElement data;

    public RpcException(int code, String message) {
        this(code, message, null);
    }

    public RpcException(int code, String message, @Nullable JsonElement data) {
        super(message);
        this.code = code;
        this.data = data;
    }

    public static RpcException invalidParams(String message) {
        return new RpcException(RpcErrorCodes.INVALID_PARAMS, message);
    }

    public static RpcException methodNotFound(String method) {
        return new RpcException(RpcErrorCodes.METHOD_NOT_FOUND, "Unknown method: " + method);
    }

    public static RpcException illegalState(String message) {
        return new RpcException(RpcErrorCodes.ILLEGAL_STATE, message);
    }

    public int getCode() {
        return this.code;
    }

    @Nullable
    public JsonElement getData() {
        return this.data;
    }

}
