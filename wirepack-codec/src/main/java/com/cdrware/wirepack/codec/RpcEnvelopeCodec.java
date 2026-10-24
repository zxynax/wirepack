package com.cdrware.wirepack.codec;

import com.cdrware.wirepack.api.RpcEnvelope;
import com.cdrware.wirepack.api.RpcError;
import com.cdrware.wirepack.api.WirepackException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Encode/decode {@link RpcEnvelope} as a {@code KV_TREE} map (reserved keys, not a new type_id).
 */
public final class RpcEnvelopeCodec {
    private RpcEnvelopeCodec() {}

    public static boolean isRpc(Map<String, ?> tree) {
        return tree != null && RpcEnvelope.MARKER_VALUE.equals(tree.get(RpcEnvelope.MARKER_KEY));
    }

    public static Map<String, Object> encode(RpcEnvelope env) {
        if (env == null) {
            throw new WirepackException("rpc envelope is null");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(RpcEnvelope.MARKER_KEY, RpcEnvelope.MARKER_VALUE);
        out.put("kind", env.kind());
        out.put("route", env.route() == null ? "" : env.route());
        out.put("corrId", env.corrId());
        out.put("payload", env.payload());
        if (env.error() == null) {
            out.put("error", null);
        } else {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("code", env.error().code());
            err.put("message", env.error().message());
            out.put("error", err);
        }
        return out;
    }

    public static RpcEnvelope decode(Map<String, ?> tree) {
        if (!isRpc(tree)) {
            throw new WirepackException("not a wirepack RPC tree");
        }
        String kind = stringVal(tree.get("kind"));
        String route = stringVal(tree.get("route"));
        long corrId = longVal(tree.get("corrId"));
        Map<String, Object> payload = mapVal(tree.get("payload"));
        RpcError error = errorVal(tree.get("error"));
        if (!RpcEnvelope.KIND_REQ.equals(kind) && !RpcEnvelope.KIND_RES.equals(kind)) {
            throw new WirepackException("rpc kind must be req or res");
        }
        return new RpcEnvelope(kind, route, corrId, payload, error);
    }

    private static String stringVal(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static long longVal(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v == null) {
            throw new WirepackException("rpc corrId missing");
        }
        return Long.parseLong(String.valueOf(v));
    }

    private static Map<String, Object> mapVal(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        throw new WirepackException("rpc payload must be an object");
    }

    private static RpcError errorVal(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Map<?, ?> m) {
            Object code = m.get("code");
            Object message = m.get("message");
            return new RpcError(code == null ? null : String.valueOf(code),
                    message == null ? "" : String.valueOf(message));
        }
        throw new WirepackException("rpc error must be an object");
    }
}
