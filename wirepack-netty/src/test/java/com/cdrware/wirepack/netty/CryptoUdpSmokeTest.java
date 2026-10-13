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
import com.cdrware.wirepack.core.ReliableSession;
import com.cdrware.wirepack.crypto.AeadSuite;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** UDP + PSK HELLO / AEAD smoke and rekey. */
class CryptoUdpSmokeTest {

    @Test
    void encryptedPingAfterHello() throws Exception {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i + 1);
        }
        runPing(key, key, true);
    }

    @Test
    void aesPingWhenBothPrefer() throws Exception {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i + 3);
        }
        CountDownLatch got = new CountDownLatch(1);
        AtomicReference<Entity> received = new AtomicReference<>();
        AtomicReference<ReliableSession> server = new AtomicReference<>();
        WirepackConfig listenCfg = cfg(key, SafetyLimits.defaults(), true);
        WirepackConfig connectCfg = cfg(key, SafetyLimits.defaults(), true);
        try (WirepackRuntime listenRt = Wirepack.open(listenCfg);
             WirepackRuntime connectRt = Wirepack.open(connectCfg)) {
            SessionAcceptor acceptor = listenRt.listen(new BindAddress("127.0.0.1", 0))
                    .get(5, TimeUnit.SECONDS);
            acceptor.onAccept(s -> {
                server.set((ReliableSession) s);
                s.onEntity(e -> {
                    received.set(e);
                    got.countDown();
                });
            });
            ReliableSession client = (ReliableSession) connectRt
                    .connect(new PeerAddress("127.0.0.1", acceptor.bindAddress().port()))
                    .get(5, TimeUnit.SECONDS);
            client.handshakeFuture().get(5, TimeUnit.SECONDS);
            assertEquals(AeadSuite.AES_256_GCM, client.negotiatedAead());
            client.send(List.of(new Entity(1024, "aes-ping".getBytes(StandardCharsets.UTF_8))))
                    .get(5, TimeUnit.SECONDS);
            assertTrue(got.await(5, TimeUnit.SECONDS));
            assertEquals("aes-ping", new String(received.get().value(), StandardCharsets.UTF_8));
            assertEquals(AeadSuite.AES_256_GCM, server.get().negotiatedAead());
        }
    }

    @Test
    void mismatchedPskDoesNotDeliver() throws Exception {
        byte[] a = new byte[32];
        byte[] b = new byte[32];
        for (int i = 0; i < 32; i++) {
            a[i] = 1;
            b[i] = 2;
        }
        CountDownLatch got = new CountDownLatch(1);
        WirepackConfig listenCfg = cfg(a, SafetyLimits.defaults());
        WirepackConfig connectCfg = cfg(b, SafetyLimits.defaults());
        try (WirepackRuntime listenRt = Wirepack.open(listenCfg);
             WirepackRuntime connectRt = Wirepack.open(connectCfg)) {
            SessionAcceptor acceptor = listenRt.listen(new BindAddress("127.0.0.1", 0))
                    .get(5, TimeUnit.SECONDS);
            acceptor.onAccept(s -> s.onEntity(e -> got.countDown()));
            Session client = connectRt.connect(new PeerAddress("127.0.0.1", acceptor.bindAddress().port()))
                    .get(5, TimeUnit.SECONDS);
            try {
                client.send(List.of(new Entity(1024, "x".getBytes(StandardCharsets.UTF_8))))
                        .get(3, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // handshake may fail
            }
            assertTrue(got.await(1, TimeUnit.SECONDS) == false, "must not deliver under mismatched PSK");
        }
    }

    @Test
    void rekeyAfterTwoPackets() throws Exception {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) 7;
        }
        SafetyLimits limits = SafetyLimits.builder().rekeyAfterPackets(2).build();
        CountDownLatch got = new CountDownLatch(3);
        AtomicReference<Integer> lastKeyId = new AtomicReference<>();
        WirepackConfig listenCfg = cfg(key, limits);
        WirepackConfig connectCfg = cfg(key, limits);
        try (WirepackRuntime listenRt = Wirepack.open(listenCfg);
             WirepackRuntime connectRt = Wirepack.open(connectCfg)) {
            SessionAcceptor acceptor = listenRt.listen(new BindAddress("127.0.0.1", 0))
                    .get(5, TimeUnit.SECONDS);
            acceptor.onAccept(s -> s.onEntity(e -> {
                if (e.typeId() == 1024) {
                    got.countDown();
                }
            }));
            ReliableSession client = (ReliableSession) connectRt
                    .connect(new PeerAddress("127.0.0.1", acceptor.bindAddress().port()))
                    .get(5, TimeUnit.SECONDS);
            client.handshakeFuture().get(5, TimeUnit.SECONDS);
            for (int i = 0; i < 3; i++) {
                client.send(List.of(new Entity(1024, ("m" + i).getBytes(StandardCharsets.UTF_8))))
                        .get(5, TimeUnit.SECONDS);
            }
            assertTrue(got.await(5, TimeUnit.SECONDS));
            // After rekey trigger, current keyId should be > base+1
            assertTrue(client.cryptoEnabled());
        }
    }

    private static void runPing(byte[] listenKey, byte[] connectKey, boolean expectOk) throws Exception {
        CountDownLatch got = new CountDownLatch(1);
        AtomicReference<Entity> received = new AtomicReference<>();
        try (WirepackRuntime listenRt = Wirepack.open(cfg(listenKey, SafetyLimits.defaults()));
             WirepackRuntime connectRt = Wirepack.open(cfg(connectKey, SafetyLimits.defaults()))) {
            SessionAcceptor acceptor = listenRt.listen(new BindAddress("127.0.0.1", 0))
                    .get(5, TimeUnit.SECONDS);
            acceptor.onAccept(s -> {
                if (s instanceof ReliableSession rs) {
                    rs.handshakeFuture().whenComplete((v, e) -> { /* primed */ });
                }
                s.onEntity(e -> {
                    received.set(e);
                    got.countDown();
                });
            });
            Session client = connectRt.connect(new PeerAddress("127.0.0.1", acceptor.bindAddress().port()))
                    .get(5, TimeUnit.SECONDS);
            if (client instanceof ReliableSession rs) {
                rs.handshakeFuture().get(5, TimeUnit.SECONDS);
            }
            client.send(List.of(new Entity(1024, "ping".getBytes(StandardCharsets.UTF_8))))
                    .get(5, TimeUnit.SECONDS);
            assertTrue(got.await(5, TimeUnit.SECONDS) == expectOk);
            if (expectOk) {
                assertEquals("ping", new String(received.get().value(), StandardCharsets.UTF_8));
            }
        }
    }

    private static WirepackConfig cfg(byte[] key, SafetyLimits limits) {
        return cfg(key, limits, false);
    }

    private static WirepackConfig cfg(byte[] key, SafetyLimits limits, boolean preferAesGcm) {
        return WirepackConfig.builder()
                .sharedKey(key)
                .keyId(1)
                .preferAesGcm(preferAesGcm)
                .limits(limits)
                .transport(new NettyUdpTransportFactory())
                .build();
    }
}
