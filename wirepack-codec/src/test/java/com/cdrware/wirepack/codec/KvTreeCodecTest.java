package com.cdrware.wirepack.codec;

import com.cdrware.wirepack.api.BuiltinTypeId;
import com.cdrware.wirepack.api.Entity;
import com.cdrware.wirepack.api.SafetyLimits;
import com.cdrware.wirepack.api.WirepackException;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KvTreeCodecTest {

    private final SafetyLimits limits = SafetyLimits.defaults();

    @Test
    void nestedObjectRoundtrip() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> mid = new LinkedHashMap<>();
        Map<String, Object> leaf = new LinkedHashMap<>();
        leaf.put("x", 1L);
        mid.put("object", leaf);
        root.put("object", mid);

        byte[] encoded = KvTreeCodec.encode(root, limits);
        Map<String, Object> back = KvTreeCodec.decode(encoded, limits);

        assertEquals(root.keySet(), back.keySet());
        @SuppressWarnings("unchecked")
        Map<String, Object> midBack = (Map<String, Object>) back.get("object");
        @SuppressWarnings("unchecked")
        Map<String, Object> leafBack = (Map<String, Object>) midBack.get("object");
        assertEquals(1L, leafBack.get("x"));
    }

    @Test
    void sevenLevelNestingRoundtrip() {
        Map<String, Object> cur = new LinkedHashMap<>();
        Map<String, Object> root = cur;
        for (int i = 0; i < 7; i++) {
            Map<String, Object> child = new LinkedHashMap<>();
            cur.put("object", child);
            cur = child;
        }
        cur.put("done", true);

        Map<String, Object> back = KvTreeCodec.decode(KvTreeCodec.encode(root, limits), limits);
        Object walk = back;
        for (int i = 0; i < 7; i++) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) walk;
            walk = m.get("object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> tip = (Map<String, Object>) walk;
        assertEquals(true, tip.get("done"));
    }

    @Test
    void arrayAndPrimitivesRoundtrip() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("name", "wire");
        root.put("n", 42L);
        root.put("f", 1.5);
        root.put("ok", true);
        root.put("nil", null);
        root.put("bin", new byte[]{1, 2, 3});
        List<Object> arr = new ArrayList<>();
        arr.add("a");
        arr.add(2L);
        root.put("items", arr);

        Map<String, Object> back = KvTreeCodec.decode(KvTreeCodec.encode(root, limits), limits);
        assertEquals("wire", back.get("name"));
        assertEquals(42L, back.get("n"));
        assertEquals(1.5, (Double) back.get("f"), 1e-9);
        assertEquals(true, back.get("ok"));
        assertEquals(null, back.get("nil"));
        assertArrayEquals(new byte[]{1, 2, 3}, (byte[]) back.get("bin"));
        @SuppressWarnings("unchecked")
        List<Object> items = (List<Object>) back.get("items");
        assertEquals(List.of("a", 2L), items);
    }

    @Test
    void toEntityUsesKvTreeTypeId() {
        Entity e = KvTreeCodec.toEntity(Map.of("a", 1L), limits);
        assertEquals(BuiltinTypeId.KV_TREE.id(), e.typeId());
        Map<String, Object> back = KvTreeCodec.decodeEntity(e, limits);
        assertEquals(1L, back.get("a"));
    }

    @Test
    void rejectsParentNotLessThanId() {
        // Manually craft: id=1 parent=1
        ByteBuffer buf = ByteBuffer.allocate(4 + 4 + 4 + 2 + 1 + 1).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(1);
        buf.putInt(1); // id
        buf.putInt(1); // parent == id → illegal
        buf.putShort((short) 1);
        buf.put((byte) 'k');
        buf.put((byte) 0); // NULL
        byte[] raw = buf.array();
        assertThrows(WirepackException.class, () -> KvTreeCodec.decode(raw, limits));
    }

    @Test
    void rejectsTooManyEntries() {
        SafetyLimits tight = SafetyLimits.builder().maxKvEntries(2).build();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("a", 1L);
        root.put("b", 2L);
        root.put("c", 3L);
        assertThrows(WirepackException.class, () -> KvTreeCodec.encode(root, tight));
    }

    @Test
    void rejectsExcessDepth() {
        SafetyLimits tight = SafetyLimits.builder().maxKvDepth(2).build();
        Map<String, Object> a = new LinkedHashMap<>();
        Map<String, Object> b = new LinkedHashMap<>();
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("x", 1L);
        b.put("object", c);
        a.put("object", b);
        assertThrows(WirepackException.class, () -> KvTreeCodec.encode(a, tight));
    }

    @Test
    void wideFlatTreeAllowedByDepth() {
        // Depth 1 with many siblings — depth cap must not reject; entries cap may.
        SafetyLimits caps = SafetyLimits.builder().maxKvDepth(2).maxKvEntries(100).build();
        Map<String, Object> root = new LinkedHashMap<>();
        for (int i = 0; i < 50; i++) {
            root.put("k" + i, i);
        }
        Map<String, Object> back = KvTreeCodec.decode(KvTreeCodec.encode(root, caps), caps);
        assertEquals(50, back.size());
        assertTrue(back.containsKey("k49"));
        assertInstanceOf(Long.class, back.get("k0"));
    }
}
