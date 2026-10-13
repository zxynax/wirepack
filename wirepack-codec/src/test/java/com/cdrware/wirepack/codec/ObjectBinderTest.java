package com.cdrware.wirepack.codec;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ObjectBinderTest {

    public record Line(String sku, long qty) {}

    public record Order(String id, Line line, List<String> tags, byte[] raw) {}

    public static class BeanOrder {
        private String id;
        private long total;

        public BeanOrder() {}

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public long getTotal() { return total; }
        public void setTotal(long total) { this.total = total; }
    }

    @Test
    void recordRoundtrip() {
        Order o = new Order("o1", new Line("sku", 3L), List.of("a", "b"), new byte[]{9, 8});
        Map<String, Object> tree = ObjectBinder.toTree(o);
        Order back = ObjectBinder.fromTree(tree, Order.class);
        assertEquals(o.id(), back.id());
        assertEquals(o.line(), back.line());
        assertEquals(o.tags(), back.tags());
        assertArrayEquals(o.raw(), back.raw());
    }

    @Test
    void beanRoundtrip() {
        BeanOrder o = new BeanOrder();
        o.setId("b1");
        o.setTotal(99L);
        BeanOrder back = ObjectBinder.fromTree(ObjectBinder.toTree(o), BeanOrder.class);
        assertEquals("b1", back.getId());
        assertEquals(99L, back.getTotal());
    }

    @Test
    void nestedThroughKvTree() {
        Order o = new Order("o2", new Line("x", 1L), List.of(), new byte[0]);
        Map<String, Object> tree = ObjectBinder.toTree(o);
        byte[] wire = KvTreeCodec.encode(tree, SafetyLimitsHolder.LIMITS);
        Map<String, Object> backTree = KvTreeCodec.decode(wire, SafetyLimitsHolder.LIMITS);
        Order back = ObjectBinder.fromTree(backTree, Order.class);
        assertEquals(o.id(), back.id());
        assertEquals(o.line(), back.line());
        assertEquals(o.tags(), back.tags());
        assertArrayEquals(o.raw(), back.raw());
    }

    /** Avoid importing SafetyLimits into every assert line. */
    private static final class SafetyLimitsHolder {
        static final com.cdrware.wirepack.api.SafetyLimits LIMITS =
                com.cdrware.wirepack.api.SafetyLimits.defaults();
    }
}
