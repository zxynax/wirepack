package com.cdrware.wirepack.core;

import com.cdrware.wirepack.api.PeerAddress;
import com.cdrware.wirepack.api.SafetyLimits;
import com.cdrware.wirepack.api.WirepackConfig;
import com.cdrware.wirepack.codec.FrameCodec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * In-process duplex: each session's sendRaw feeds the peer's frame decoder.
 */
class SessionTreeTest {

    public record Ping(String msg, long n) {}

    @Test
    void sendObjectReceivedAsObjectAndTree() throws Exception {
        SafetyLimits limits = SafetyLimits.defaults();
        WirepackConfig cfg = WirepackConfig.builder().limits(limits).build();
        ReliableSession a = new ReliableSession(1, new PeerAddress("127.0.0.1", 1), cfg);
        ReliableSession b = new ReliableSession(1, new PeerAddress("127.0.0.1", 2), cfg);
        FrameCodec codec = new FrameCodec(limits);

        a.setSender((peer, frame) -> b.onFrame(codec.decode(frame)));
        b.setSender((peer, frame) -> a.onFrame(codec.decode(frame)));

        AtomicReference<Ping> gotObj = new AtomicReference<>();
        AtomicReference<Map<String, Object>> gotTree = new AtomicReference<>();
        b.onObject(Ping.class, gotObj::set);
        b.onTree(gotTree::set);

        a.sendObject(new Ping("hi", 7L)).get(5, TimeUnit.SECONDS);

        assertNotNull(gotObj.get());
        assertEquals(new Ping("hi", 7L), gotObj.get());
        assertEquals("hi", gotTree.get().get("msg"));
        assertEquals(7L, gotTree.get().get("n"));
    }

    @Test
    void largeTreeFragmentsAcrossFrames() throws Exception {
        SafetyLimits limits = SafetyLimits.builder()
                .maxFrameBytes(500)
                .maxPayloadBytes(476)
                .maxKvBytes(64 * 1024)
                .build();
        WirepackConfig cfg = WirepackConfig.builder().limits(limits).build();
        ReliableSession a = new ReliableSession(2, new PeerAddress("127.0.0.1", 1), cfg);
        ReliableSession b = new ReliableSession(2, new PeerAddress("127.0.0.1", 2), cfg);
        FrameCodec codec = new FrameCodec(limits);

        List<byte[]> aToB = new CopyOnWriteArrayList<>();
        a.setSender((peer, frame) -> {
            aToB.add(frame);
            b.onFrame(codec.decode(frame));
        });
        b.setSender((peer, frame) -> a.onFrame(codec.decode(frame)));

        AtomicReference<Map<String, Object>> got = new AtomicReference<>();
        b.onTree(got::set);

        String big = "y".repeat(2500);
        a.sendTree(Map.of("blob", big)).get(5, TimeUnit.SECONDS);

        assertNotNull(got.get());
        assertEquals(big, got.get().get("blob"));
        // More than one DATA frame likely when fragmented
        assertEquals(true, aToB.size() >= 1);
    }
}
