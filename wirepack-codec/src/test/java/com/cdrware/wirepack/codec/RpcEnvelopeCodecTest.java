package com.cdrware.wirepack.codec;

import com.cdrware.wirepack.api.RpcEnvelope;
import com.cdrware.wirepack.api.RpcError;
import com.cdrware.wirepack.api.SafetyLimits;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RpcEnvelopeCodecTest {

    @Test
    void requestRoundtripThroughKvTree() {
        RpcEnvelope env = RpcEnvelope.request("echo", 42L, Map.of("msg", "hi"));
        Map<String, Object> tree = RpcEnvelopeCodec.encode(env);
        assertTrue(RpcEnvelopeCodec.isRpc(tree));
        assertFalse(RpcEnvelopeCodec.isRpc(Map.of("msg", "hi")));

        byte[] wire = KvTreeCodec.encode(tree, SafetyLimits.defaults());
        Map<String, Object> back = KvTreeCodec.decode(wire, SafetyLimits.defaults());
        RpcEnvelope decoded = RpcEnvelopeCodec.decode(back);

        assertEquals(RpcEnvelope.KIND_REQ, decoded.kind());
        assertEquals("echo", decoded.route());
        assertEquals(42L, decoded.corrId());
        assertEquals("hi", decoded.payload().get("msg"));
        assertNull(decoded.error());
    }

    @Test
    void errorResponseRoundtrip() {
        RpcEnvelope env = RpcEnvelope.response("echo", 7L, null,
                new RpcError(RpcError.NOT_FOUND, "no handler for route: echo"));
        RpcEnvelope decoded = RpcEnvelopeCodec.decode(RpcEnvelopeCodec.encode(env));
        assertTrue(decoded.isResponse());
        assertEquals(RpcError.NOT_FOUND, decoded.error().code());
        assertEquals("no handler for route: echo", decoded.error().message());
    }
}
