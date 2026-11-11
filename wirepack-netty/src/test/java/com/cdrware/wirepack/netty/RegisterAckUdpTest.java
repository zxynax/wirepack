package com.cdrware.wirepack.netty;

import com.cdrware.wirepack.api.BindAddress;
import com.cdrware.wirepack.api.Entity;
import com.cdrware.wirepack.api.FieldDef;
import com.cdrware.wirepack.api.PeerAddress;
import com.cdrware.wirepack.api.RegisterAck;
import com.cdrware.wirepack.api.RegisteredSchema;
import com.cdrware.wirepack.api.SafetyLimits;
import com.cdrware.wirepack.api.Session;
import com.cdrware.wirepack.api.SessionAcceptor;
import com.cdrware.wirepack.api.WireType;
import com.cdrware.wirepack.api.Wirepack;
import com.cdrware.wirepack.api.WirepackConfig;
import com.cdrware.wirepack.api.WirepackException;
import com.cdrware.wirepack.api.WirepackRuntime;
import com.cdrware.wirepack.codec.RegisterCodec;
import com.cdrware.wirepack.codec.TypeRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** REGISTER, REGISTER_ACK, then a typed row. A conflicting redefinition is quarantined. */
class RegisterAckUdpTest {

    @Test
    void registerAckThenTypedData() throws Exception {
        RegisteredSchema sku = RegisterCodec.schema(1024, "sku", List.of(
                new FieldDef("sku_id", WireType.U64),
                new FieldDef("qty", WireType.U32)
        ));
        CountDownLatch gotRow = new CountDownLatch(1);
        AtomicReference<Map<String, Object>> row = new AtomicReference<>();
        AtomicReference<Session> listener = new AtomicReference<>();

        try (WirepackRuntime listenRt = Wirepack.open(config());
             WirepackRuntime connectRt = Wirepack.open(config())) {
            SessionAcceptor acceptor = listenRt.listen(new BindAddress("127.0.0.1", 0))
                    .get(5, TimeUnit.SECONDS);
            acceptor.onAccept(session -> {
                listener.set(session);
                session.onEntity(entity -> {
                    if (entity.typeId() != sku.typeId()) {
                        return;
                    }
                    RegisteredSchema stored = session.registeredSchema(sku.typeId()).orElseThrow();
                    row.set(RegisterCodec.decodeFields(stored, entity.valueRaw(), SafetyLimits.defaults()));
                    gotRow.countDown();
                });
            });

            Session client = connectRt.connect(new PeerAddress("127.0.0.1", acceptor.bindAddress().port()))
                    .get(5, TimeUnit.SECONDS);
            RegisterAck ack = client.register(sku).get(5, TimeUnit.SECONDS);
            assertTrue(ack.accepted());
            assertEquals(sku.typeId(), ack.typeId());
            assertEquals(sku.schemaHash(), ack.schemaHash());
            assertEquals(sku.schemaHash(), listener.get().registeredSchema(sku.typeId()).orElseThrow().schemaHash());

            TypeRegistry local = new TypeRegistry();
            local.register(sku);
            client.send(local.encodeEntity(sku.typeId(), Map.of("sku_id", 42L, "qty", 3))).get(5, TimeUnit.SECONDS);

            assertTrue(gotRow.await(5, TimeUnit.SECONDS), "typed row was not delivered");
            assertEquals(42L, ((Number) row.get().get("sku_id")).longValue());
            assertEquals(3L, ((Number) row.get().get("qty")).longValue());
        }
    }

    @Test
    void redefineMismatchQuarantinesTheType() throws Exception {
        RegisteredSchema sku = RegisterCodec.schema(1024, "sku", List.of(
                new FieldDef("sku_id", WireType.U64)
        ));
        RegisteredSchema other = RegisterCodec.schema(1024, "sku", List.of(
                new FieldDef("name", WireType.STRING)
        ));
        AtomicInteger deliveries = new AtomicInteger();
        AtomicReference<Session> listener = new AtomicReference<>();

        try (WirepackRuntime listenRt = Wirepack.open(config());
             WirepackRuntime connectRt = Wirepack.open(config())) {
            SessionAcceptor acceptor = listenRt.listen(new BindAddress("127.0.0.1", 0))
                    .get(5, TimeUnit.SECONDS);
            acceptor.onAccept(session -> {
                listener.set(session);
                session.onEntity(entity -> {
                    if (entity.typeId() == sku.typeId()) {
                        deliveries.incrementAndGet();
                    }
                });
            });

            Session client = connectRt.connect(new PeerAddress("127.0.0.1", acceptor.bindAddress().port()))
                    .get(5, TimeUnit.SECONDS);
            assertTrue(client.register(sku).get(5, TimeUnit.SECONDS).accepted());

            ExecutionException rejected = org.junit.jupiter.api.Assertions.assertThrows(
                    ExecutionException.class,
                    () -> client.register(other).get(5, TimeUnit.SECONDS));
            assertInstanceOf(WirepackException.class, rejected.getCause());
            assertEquals(sku.schemaHash(), listener.get().registeredSchema(sku.typeId()).orElseThrow().schemaHash());

            Entity bad = new Entity(sku.typeId(), "nope".getBytes());
            client.send(bad).get(5, TimeUnit.SECONDS);
            Thread.sleep(300);
            assertEquals(0, deliveries.get(), "quarantined type must not be delivered");
        }
    }

    private static WirepackConfig config() {
        byte[] key = new byte[16];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i + 3);
        }
        return WirepackConfig.builder()
                .sharedKey(key)
                .keyId(1)
                .limits(SafetyLimits.defaults())
                .transport(new NettyUdpTransportFactory())
                .build();
    }
}
