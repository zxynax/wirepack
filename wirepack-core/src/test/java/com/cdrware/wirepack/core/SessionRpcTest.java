package com.cdrware.wirepack.core;

import com.cdrware.wirepack.api.PeerAddress;
import com.cdrware.wirepack.api.RpcError;
import com.cdrware.wirepack.api.RpcException;
import com.cdrware.wirepack.api.SafetyLimits;
import com.cdrware.wirepack.api.WirepackConfig;
import com.cdrware.wirepack.api.WirepackMapping;
import com.cdrware.wirepack.codec.FrameCodec;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionRpcTest {

    public record EchoIn(String msg) {}

    public record EchoOut(String msg) {}

    public static final class EchoBean {
        @WirepackMapping("echo")
        public EchoOut echo(EchoIn in) {
            return new EchoOut(in.msg());
        }
    }

    @Test
    void requestCompletesWithMappedResponse() throws Exception {
        Duplex d = Duplex.open();
        WirepackMappings.install(d.server, new EchoBean());

        EchoOut out = d.client.request("echo", new EchoIn("hi"), EchoOut.class)
                .get(5, TimeUnit.SECONDS);
        assertEquals("hi", out.msg());
    }

    @Test
    void unknownRouteReturnsNotFound() {
        Duplex d = Duplex.open();
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> d.client.request("missing", new EchoIn("x"), EchoOut.class)
                        .get(5, TimeUnit.SECONDS));
        RpcException rpc = assertInstanceOf(RpcException.class, ex.getCause());
        assertEquals(RpcError.NOT_FOUND, rpc.code());
        assertTrue(rpc.getMessage().contains("missing"));
    }

    @Test
    void timeoutWhenPeerNeverReplies() {
        Duplex d = Duplex.openDropReplies();
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> d.client.request("echo", new EchoIn("x"), EchoOut.class, Duration.ofMillis(80))
                        .get(5, TimeUnit.SECONDS));
        assertInstanceOf(java.util.concurrent.TimeoutException.class, ex.getCause());
    }

    private static final class Duplex {
        final ReliableSession client;
        final ReliableSession server;

        private Duplex(ReliableSession client, ReliableSession server) {
            this.client = client;
            this.server = server;
        }

        static Duplex open() {
            SafetyLimits limits = SafetyLimits.defaults();
            WirepackConfig cfg = WirepackConfig.builder().limits(limits).build();
            ReliableSession a = new ReliableSession(1, new PeerAddress("127.0.0.1", 1), cfg);
            ReliableSession b = new ReliableSession(1, new PeerAddress("127.0.0.1", 2), cfg);
            FrameCodec codec = new FrameCodec(limits);
            a.setSender((peer, frame) -> b.onFrame(codec.decode(frame)));
            b.setSender((peer, frame) -> a.onFrame(codec.decode(frame)));
            return new Duplex(a, b);
        }

        static Duplex openDropReplies() {
            SafetyLimits limits = SafetyLimits.defaults();
            WirepackConfig cfg = WirepackConfig.builder().limits(limits).build();
            ReliableSession a = new ReliableSession(1, new PeerAddress("127.0.0.1", 1), cfg);
            ReliableSession b = new ReliableSession(1, new PeerAddress("127.0.0.1", 2), cfg);
            a.setSender((peer, frame) -> { /* drop — no peer response */ });
            return new Duplex(a, b);
        }
    }
}
