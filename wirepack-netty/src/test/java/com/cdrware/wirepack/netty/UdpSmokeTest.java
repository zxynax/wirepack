package com.cdrware.wirepack.netty;

import com.cdrware.wirepack.api.BindAddress;
import com.cdrware.wirepack.api.Entity;
import com.cdrware.wirepack.api.PeerAddress;
import com.cdrware.wirepack.api.SafetyLimits;
import com.cdrware.wirepack.api.Session;
import com.cdrware.wirepack.api.SessionAcceptor;
import com.cdrware.wirepack.api.Wirepack;
import com.cdrware.wirepack.api.WirepackConfig;
import com.cdrware.wirepack.api.WirepackRuntime;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end UDP listen/connect smoke. */
class UdpSmokeTest {

    @Test
    void listenConnectDeliversPing() throws Exception {
        byte[] key = new byte[16];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) i;
        }

        WirepackConfig listenCfg = WirepackConfig.builder()
                .sharedKey(key)
                .keyId(1)
                .limits(SafetyLimits.defaults())
                .transport(new NettyUdpTransportFactory())
                .build();
        WirepackConfig connectCfg = WirepackConfig.builder()
                .sharedKey(key)
                .keyId(1)
                .limits(SafetyLimits.defaults())
                .transport(new NettyUdpTransportFactory())
                .build();

        CountDownLatch gotPing = new CountDownLatch(1);
        AtomicReference<Entity> received = new AtomicReference<>();

        try (WirepackRuntime listenRt = Wirepack.open(listenCfg);
             WirepackRuntime connectRt = Wirepack.open(connectCfg)) {

            SessionAcceptor acceptor = listenRt.listen(new BindAddress("127.0.0.1", 0))
                    .get(5, TimeUnit.SECONDS);
            int port = acceptor.bindAddress().port();
            assertTrue(port > 0, "ephemeral port must be resolved");

            acceptor.onAccept(session -> session.onEntity(e -> {
                received.set(e);
                gotPing.countDown();
            }));

            Session client = connectRt.connect(new PeerAddress("127.0.0.1", port))
                    .get(5, TimeUnit.SECONDS);

            Entity ping = new Entity(1024, "ping".getBytes(StandardCharsets.UTF_8));
            client.send(List.of(ping)).get(5, TimeUnit.SECONDS);

            assertTrue(gotPing.await(5, TimeUnit.SECONDS), "listener did not receive ping");
            Entity e = received.get();
            assertEquals(1024, e.typeId());
            assertEquals("ping", new String(e.value(), StandardCharsets.UTF_8));
        }
    }
}
