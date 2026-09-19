package com.cdrware.wirepack.codec;

import com.cdrware.wirepack.api.Entity;
import com.cdrware.wirepack.api.FieldDef;
import com.cdrware.wirepack.api.FrameHeader;
import com.cdrware.wirepack.api.MsgType;
import com.cdrware.wirepack.api.RegisteredSchema;
import com.cdrware.wirepack.api.SafetyLimits;
import com.cdrware.wirepack.api.WireType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityCodecTest {

    private final SafetyLimits limits = SafetyLimits.defaults();

    @Test
    void multiEntityPackRoundtrip() {
        Entity a = new Entity(1024, new byte[]{1, 2});
        Entity b = new Entity(1025, new byte[]{3, 4, 5});
        byte[] payload = EntityWriter.pack(limits, List.of(a, b), true);

        EntityReader reader = new EntityReader(payload, limits);
        List<Entity> read = reader.readAll();
        assertEquals(2, read.size());
        assertEquals(1024, read.get(0).typeId());
        assertArrayEquals(new byte[]{1, 2}, read.get(0).valueRaw());
        assertEquals(1025, read.get(1).typeId());
        assertArrayEquals(new byte[]{3, 4, 5}, read.get(1).valueRaw());
    }

    @Test
    void skipUnknownTypeId() {
        // Pack: known, unknown, known — reader returns all; app skips unknown by type_id check
        Entity known1 = new Entity(1024, new byte[]{9});
        Entity unknown = new Entity(5000, new byte[]{7, 7, 7});
        Entity known2 = new Entity(1024, new byte[]{8});
        byte[] payload = EntityWriter.pack(limits, List.of(known1, unknown, known2), false);

        EntityReader reader = new EntityReader(payload, limits);
        List<Entity> all = reader.readAll();
        assertEquals(3, all.size());

        // Simulate skip: only process registered type_ids
        List<Entity> known = all.stream().filter(e -> e.typeId() == 1024).toList();
        assertEquals(2, known.size());
        // unknown was "skipped" by consumer (still present in stream for forward compat)
        assertTrue(all.stream().anyMatch(e -> e.typeId() == 5000));
        assertEquals(3, unknown.length());
        assertFalse(reader.hasRemaining());
    }

    @Test
    void registerThenEncodeSkuLikeRow() {
        TypeRegistry registry = new TypeRegistry(limits);
        RegisteredSchema sku = RegisterCodec.schema(1024, "sku", List.of(
                new FieldDef("sku_id", WireType.U64),
                new FieldDef("qty", WireType.U32),
                new FieldDef("name", WireType.STRING)
        ));

        Entity registerEntity = RegisterCodec.toEntity(sku, limits);
        registry.registerFromEntity(registerEntity);

        // Roundtrip REGISTER bytes
        RegisteredSchema decoded = RegisterCodec.decodeRegister(registerEntity.valueRaw(), limits);
        assertEquals(sku.typeId(), decoded.typeId());
        assertEquals(sku.name(), decoded.name());
        assertEquals(sku.fields().size(), decoded.fields().size());

        Map<String, Object> row = Map.of(
                "sku_id", 99_001L,
                "qty", 12,
                "name", "Widget-A"
        );
        Entity app = registry.encodeEntity(1024, row);
        assertEquals(1024, app.typeId());

        Map<String, Object> back = registry.decodeEntity(app);
        assertEquals(99_001L, ((Number) back.get("sku_id")).longValue());
        assertEquals(12L, ((Number) back.get("qty")).longValue());
        assertEquals("Widget-A", back.get("name"));

        // Pack into a DATA frame
        byte[] payload = EntityWriter.pack(limits, List.of(registerEntity, app), true);
        FrameHeader header = new FrameHeader(
                FrameHeader.VERSION_V0, 1, MsgType.DATA, 1, 0, 1, 0, payload.length);
        byte[] frame = new FrameCodec(limits).encode(header, payload);
        FrameCodec.DecodedFrame df = new FrameCodec(limits).decode(frame);
        List<Entity> entities = new EntityReader(df.payloadRaw(), limits).readAll();
        assertEquals(2, entities.size());
        assertEquals(1, entities.get(0).typeId()); // REGISTER
        assertEquals(1024, entities.get(1).typeId());
    }

    @Test
    void entityFragRoundtrip() {
        byte[] uid = new byte[]{1, 2, 3};
        byte[] data = "hello-frag".getBytes();
        Entity frag = RegisterCodec.toFragEntity(uid, 0, 2, data);
        RegisterCodec.EntityFrag decoded = RegisterCodec.decodeEntityFrag(frag.valueRaw());
        assertArrayEquals(uid, decoded.uid());
        assertEquals(0, decoded.fragIndex());
        assertEquals(2, decoded.fragCount());
        assertArrayEquals(data, decoded.data());
    }
}
