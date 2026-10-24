package com.cdrware.wirepack.api;

/**
 * RPC failure carried in a response envelope ({@code error.code} / {@code error.message}).
 */
public record RpcError(String code, String message) {
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String HANDLER = "HANDLER";
    public static final String BIND = "BIND";

    public RpcError {
        code = code == null || code.isBlank() ? HANDLER : code;
        message = message == null ? "" : message;
    }
}
