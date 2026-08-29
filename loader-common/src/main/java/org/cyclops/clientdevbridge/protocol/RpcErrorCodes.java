package org.cyclops.clientdevbridge.protocol;

/**
 * JSON-RPC 2.0 error codes, plus the ClientDevBridge-specific codes in the implementation-defined range.
 *
 * These values are part of the wire protocol and are identical on every branch.
 *
 * @author rubensworks
 */
public class RpcErrorCodes {

    // Standard JSON-RPC 2.0 codes.
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    // ClientDevBridge codes, in the -32000..-32099 implementation-defined range.
    /** The game is not in a state where this method makes sense (no world, no screen, ...). */
    public static final int ILLEGAL_STATE = -32001;
    /** A wait or condition did not become true within its timeout. */
    public static final int TIMEOUT = -32002;
    /** The method is gated behind a system property that is not set. */
    public static final int DISABLED = -32003;

}
