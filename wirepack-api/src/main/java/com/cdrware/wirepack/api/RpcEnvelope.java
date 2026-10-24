package com.cdrware.wirepack.api;

import java.util.Map;

/**
 * Logical RPC document carried as a {@code KV_TREE} (see PROTOCOL.md).
 *
 * <p>Reserved keys: {@code _wp}, {@code kind}, {@code route}, {@code corrId},
 * {@code payload}, {@code error}.
 */
public record RpcEnvelope(
        String kind,
        String route,
        long corrId,
        Map<String, Object> payload,
        RpcError error
) {
    public static final String MARKER_KEY = "_wp";
    public static final String MARKER_VALUE = "rpc";
    public static final String KIND_REQ = "req";
    public static final String KIND_RES = "res";

    public static RpcEnvelope request(String route, long corrId, Map<String, Object> payload) {
        return new RpcEnvelope(KIND_REQ, route, corrId, payload, null);
    }

    public static RpcEnvelope response(String route, long corrId, Map<String, Object> payload, RpcError error) {
        return new RpcEnvelope(KIND_RES, route, corrId, payload, error);
    }

    public boolean isRequest() {
        return KIND_REQ.equals(kind);
    }

    public boolean isResponse() {
        return KIND_RES.equals(kind);
    }
}
