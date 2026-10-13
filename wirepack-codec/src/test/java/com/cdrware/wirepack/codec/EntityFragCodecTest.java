package com.cdrware.wirepack.codec;

import com.cdrware.wirepack.api.BuiltinTypeId;
import com.cdrware.wirepack.api.Entity;
import com.cdrware.wirepack.api.SafetyLimits;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityFragCodecTest {

    @Test
    void smallValueStaysSingleEntity() {
        SafetyLimits limits = SafetyLimits.defaults();
        byte[] value = new byte[100];
        List<Entity> wire = EntityFragCodec.toWireEntities(BuiltinTypeId.KV_TREE.id(), value, limits, 0);
        assertEquals(1, wire.size());
        assertEquals(BuiltinTypeId.KV_TREE.id(), wire.get(0).typeId());
        assertArrayEquals(value, wire.get(0).valueRaw());
    }

    @Test
    void largeValueFragmentsAndReassembles() {
        SafetyLimits limits = SafetyLimits.builder()
                .maxFrameBytes(400)
                .maxPayloadBytes(376)
                .build();
        byte[] value = new byte[2000];
        for (int i = 0; i < value.length; i++) {
            value[i] = (byte) i;
        }
        List<Entity> wire = EntityFragCodec.toWireEntities(BuiltinTypeId.KV_TREE.id(), value, limits, 0);
        assertTrue(wire.size() > 1);
        for (Entity e : wire) {
            assertEquals(BuiltinTypeId.ENTITY_FRAG.id(), e.typeId());
        }
        EntityFragCodec.Reassembler re = new EntityFragCodec.Reassembler();
        AtomicReference<Entity> done = new AtomicReference<>();
        for (Entity e : wire) {
            Optional<Entity> logical = re.accept(e);
            logical.ifPresent(done::set);
        }
        assertEquals(BuiltinTypeId.KV_TREE.id(), done.get().typeId());
        assertArrayEquals(value, done.get().valueRaw());
    }

    @Test
    void kvTreeRoundtripThroughFrags() {
        SafetyLimits limits = SafetyLimits.builder()
                .maxFrameBytes(400)
                .maxPayloadBytes(376)
                .maxKvBytes(64 * 1024)
                .build();
        Map<String, Object> root = Map.of("payload", "x".repeat(1500));
        byte[] encoded = KvTreeCodec.encode(root, limits);
        List<Entity> wire = EntityFragCodec.toWireEntities(BuiltinTypeId.KV_TREE.id(), encoded, limits, 0);
        assertTrue(wire.size() > 1);
        EntityFragCodec.Reassembler re = new EntityFragCodec.Reassembler();
        Entity logical = null;
        for (Entity e : wire) {
            Optional<Entity> o = re.accept(e);
            if (o.isPresent()) {
                logical = o.get();
            }
        }
        Map<String, Object> back = KvTreeCodec.decodeEntity(logical, limits);
        assertEquals(root.get("payload"), back.get("payload"));
    }
}
