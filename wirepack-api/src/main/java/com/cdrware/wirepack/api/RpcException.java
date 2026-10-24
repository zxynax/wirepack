package com.cdrware.wirepack.api;

/**
 * Client-side failure for {@link Session#request}: peer {@link RpcError} or local bind issues.
 */
public final class RpcException extends WirepackException {
    private final String code;

    public RpcException(String code, String message) {
        super(message == null || message.isBlank() ? code : message);
        this.code = code == null || code.isBlank() ? RpcError.HANDLER : code;
    }

    public RpcException(RpcError error) {
        this(error == null ? RpcError.HANDLER : error.code(),
                error == null ? "" : error.message());
    }

    public String code() {
        return code;
    }
}
