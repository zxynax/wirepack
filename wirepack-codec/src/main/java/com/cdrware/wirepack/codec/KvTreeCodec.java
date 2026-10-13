package com.cdrware.wirepack.codec;

import com.cdrware.wirepack.api.BuiltinTypeId;
import com.cdrware.wirepack.api.Entity;
import com.cdrware.wirepack.api.KvValueKind;
import com.cdrware.wirepack.api.SafetyLimits;
import com.cdrware.wirepack.api.WirepackException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Encode/decode {@link BuiltinTypeId#KV_TREE}: ancestor-scoped parentId K/V trees.
 *
 * <p>Protocol entity ids are wire-only (not application object fields). Root ancestor is id
 * {@code 0}. Encode assigns monotonic ids with {@code parentId < id}. Decode rebuilds
 * iteratively (no recursive descent).
 */
public final class KvTreeCodec {
    private KvTreeCodec() {}

    /**
     * Encode a root map into a {@code KV_TREE} entity value.
     *
     * @param root map of string keys to JSON-like values ({@link Map}, {@link List},
     *             {@link String}, {@link Number}, {@link Boolean}, {@code byte[]}, or null)
     */
    public static byte[] encode(Map<String, ?> root, SafetyLimits limits) {
        limits = limits == null ? SafetyLimits.defaults() : limits;
        Objects.requireNonNull(root, "root");
        List<WireEntry> entries = new ArrayList<>();
        int[] nextId = {1};
        int[] maxDepthSeen = {0};
        encodeObjectChildren(0, root, 1, entries, nextId, maxDepthSeen, limits);
        if (maxDepthSeen[0] > limits.maxKvDepth()) {
            throw new WirepackException("maxKvDepth exceeded: " + maxDepthSeen[0]);
        }
        if (entries.size() > limits.maxKvEntries()) {
            throw new WirepackException("maxKvEntries exceeded: " + entries.size());
        }
        byte[] raw = writeEntries(entries, limits);
        if (raw.length > limits.maxKvBytes()) {
            throw new WirepackException("maxKvBytes exceeded: " + raw.length);
        }
        return raw;
    }

    /** Wrap encoded bytes as a {@link BuiltinTypeId#KV_TREE} entity. */
    public static Entity toEntity(Map<String, ?> root, SafetyLimits limits) {
        return new Entity(BuiltinTypeId.KV_TREE.id(), encode(root, limits));
    }

    /**
     * Decode a {@code KV_TREE} value into a root {@link LinkedHashMap}.
     * Nested objects are maps; arrays are {@link ArrayList}s.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> decode(byte[] raw, SafetyLimits limits) {
        limits = limits == null ? SafetyLimits.defaults() : limits;
        if (raw == null || raw.length < 4) {
            throw new WirepackException("KV_TREE value too short");
        }
        if (raw.length > limits.maxKvBytes()) {
            throw new WirepackException("maxKvBytes exceeded: " + raw.length);
        }
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        int count = buf.getInt();
        if (count < 0 || count > limits.maxKvEntries()) {
            throw new WirepackException("maxKvEntries exceeded: " + count);
        }
        List<WireEntry> entries = new ArrayList<>(count);
        int lastId = 0;
        for (int i = 0; i < count; i++) {
            WireEntry e = readEntry(buf, limits);
            if (e.id <= lastId) {
                throw new WirepackException("KV_TREE ids must be strictly increasing");
            }
            if (e.parentId >= e.id) {
                throw new WirepackException("parentId must be < id (got parent="
                        + e.parentId + " id=" + e.id + ")");
            }
            lastId = e.id;
            entries.add(e);
        }
        if (buf.hasRemaining()) {
            throw new WirepackException("trailing bytes in KV_TREE value");
        }

        Map<Integer, Object> containers = new HashMap<>();
        Map<String, Object> root = new LinkedHashMap<>();
        containers.put(0, root);

        // Depth: BFS from root using parent links (iterative).
        Map<Integer, Integer> depthOf = new HashMap<>();
        depthOf.put(0, 0);

        for (WireEntry e : entries) {
            Object parent = containers.get(e.parentId);
            if (parent == null) {
                throw new WirepackException("missing parent container id=" + e.parentId);
            }
            int parentDepth = depthOf.getOrDefault(e.parentId, 0);
            int depth = parentDepth + 1;
            if (depth > limits.maxKvDepth()) {
                throw new WirepackException("maxKvDepth exceeded: " + depth);
            }
            depthOf.put(e.id, depth);

            Object attached;
            switch (e.kind) {
                case OBJECT -> {
                    Map<String, Object> child = new LinkedHashMap<>();
                    containers.put(e.id, child);
                    attached = child;
                }
                case ARRAY -> {
                    List<Object> child = new ArrayList<>();
                    containers.put(e.id, child);
                    attached = child;
                }
                default -> attached = e.leaf;
            }

            if (parent instanceof Map<?, ?> map) {
                ((Map<String, Object>) map).put(e.key, attached);
            } else if (parent instanceof List<?> list) {
                @SuppressWarnings("unchecked")
                List<Object> arr = (List<Object>) list;
                int idx;
                try {
                    idx = Integer.parseInt(e.key);
                } catch (NumberFormatException ex) {
                    // Append if key is not a decimal index
                    arr.add(attached);
                    continue;
                }
                while (arr.size() <= idx) {
                    arr.add(null);
                }
                arr.set(idx, attached);
            } else {
                throw new WirepackException("parent is not a container: " + e.parentId);
            }
        }
        return root;
    }

    public static Map<String, Object> decodeEntity(Entity entity, SafetyLimits limits) {
        Objects.requireNonNull(entity, "entity");
        if (entity.typeId() != BuiltinTypeId.KV_TREE.id()) {
            throw new WirepackException("expected KV_TREE type_id, got " + entity.typeId());
        }
        return decode(entity.valueRaw(), limits);
    }

    private static void encodeObjectChildren(
            int parentId,
            Map<String, ?> map,
            int depth,
            List<WireEntry> out,
            int[] nextId,
            int[] maxDepthSeen,
            SafetyLimits limits) {
        maxDepthSeen[0] = Math.max(maxDepthSeen[0], depth);
        if (depth > limits.maxKvDepth()) {
            throw new WirepackException("maxKvDepth exceeded: " + depth);
        }
        for (Map.Entry<String, ?> e : map.entrySet()) {
            emit(parentId, e.getKey(), e.getValue(), depth, out, nextId, maxDepthSeen, limits);
        }
    }

    private static void encodeArrayChildren(
            int parentId,
            List<?> list,
            int depth,
            List<WireEntry> out,
            int[] nextId,
            int[] maxDepthSeen,
            SafetyLimits limits) {
        maxDepthSeen[0] = Math.max(maxDepthSeen[0], depth);
        if (depth > limits.maxKvDepth()) {
            throw new WirepackException("maxKvDepth exceeded: " + depth);
        }
        for (int i = 0; i < list.size(); i++) {
            emit(parentId, Integer.toString(i), list.get(i), depth, out, nextId, maxDepthSeen, limits);
        }
    }

    private static void emit(
            int parentId,
            String key,
            Object value,
            int depth,
            List<WireEntry> out,
            int[] nextId,
            int[] maxDepthSeen,
            SafetyLimits limits) {
        if (out.size() >= limits.maxKvEntries()) {
            throw new WirepackException("maxKvEntries exceeded");
        }
        int id = nextId[0]++;
        if (parentId >= id) {
            throw new WirepackException("internal: parentId >= id");
        }
        if (value == null) {
            out.add(WireEntry.leaf(id, parentId, key, KvValueKind.NULL, null));
            return;
        }
        if (value instanceof Boolean b) {
            out.add(WireEntry.leaf(id, parentId, key, KvValueKind.BOOL, b));
            return;
        }
        if (value instanceof Float || value instanceof Double) {
            out.add(WireEntry.leaf(id, parentId, key, KvValueKind.F64, ((Number) value).doubleValue()));
            return;
        }
        if (value instanceof Number n) {
            out.add(WireEntry.leaf(id, parentId, key, KvValueKind.I64, n.longValue()));
            return;
        }
        if (value instanceof String s) {
            byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
            if (utf8.length > limits.maxStringBytes()) {
                throw new WirepackException("string too long");
            }
            out.add(WireEntry.leaf(id, parentId, key, KvValueKind.STRING, s));
            return;
        }
        if (value instanceof byte[] bytes) {
            if (bytes.length > limits.maxStringBytes()) {
                throw new WirepackException("bytes too long");
            }
            out.add(WireEntry.leaf(id, parentId, key, KvValueKind.BYTES, bytes.clone()));
            return;
        }
        if (value instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, ?> child = (Map<String, ?>) m;
            out.add(WireEntry.leaf(id, parentId, key, KvValueKind.OBJECT, null));
            encodeObjectChildren(id, child, depth + 1, out, nextId, maxDepthSeen, limits);
            return;
        }
        if (value instanceof Collection<?> c) {
            List<?> list = c instanceof List<?> l ? l : new ArrayList<>(c);
            out.add(WireEntry.leaf(id, parentId, key, KvValueKind.ARRAY, null));
            encodeArrayChildren(id, list, depth + 1, out, nextId, maxDepthSeen, limits);
            return;
        }
        throw new WirepackException("unsupported KV value type: " + value.getClass().getName());
    }

    private static byte[] writeEntries(List<WireEntry> entries, SafetyLimits limits) {
        // First pass size
        int size = 4;
        for (WireEntry e : entries) {
            byte[] key = e.key.getBytes(StandardCharsets.UTF_8);
            if (key.length > limits.maxStringBytes()) {
                throw new WirepackException("key too long");
            }
            size = Math.addExact(size, 4 + 4 + 2 + key.length + 1);
            size = Math.addExact(size, leafPayloadSize(e, limits));
        }
        ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(entries.size());
        for (WireEntry e : entries) {
            byte[] key = e.key.getBytes(StandardCharsets.UTF_8);
            buf.putInt(e.id);
            buf.putInt(e.parentId);
            buf.putShort((short) key.length);
            buf.put(key);
            buf.put((byte) e.kind.code());
            writeLeaf(buf, e, limits);
        }
        return buf.array();
    }

    private static int leafPayloadSize(WireEntry e, SafetyLimits limits) {
        return switch (e.kind) {
            case NULL, OBJECT, ARRAY -> 0;
            case BOOL -> 1;
            case I64, F64 -> 8;
            case STRING -> {
                byte[] s = ((String) e.leaf).getBytes(StandardCharsets.UTF_8);
                yield 2 + s.length;
            }
            case BYTES -> 2 + ((byte[]) e.leaf).length;
        };
    }

    private static void writeLeaf(ByteBuffer buf, WireEntry e, SafetyLimits limits) {
        switch (e.kind) {
            case NULL, OBJECT, ARRAY -> { }
            case BOOL -> buf.put((Boolean) e.leaf ? (byte) 1 : (byte) 0);
            case I64 -> buf.putLong((Long) e.leaf);
            case F64 -> buf.putDouble((Double) e.leaf);
            case STRING -> {
                byte[] s = ((String) e.leaf).getBytes(StandardCharsets.UTF_8);
                buf.putShort((short) s.length);
                buf.put(s);
            }
            case BYTES -> {
                byte[] s = (byte[]) e.leaf;
                buf.putShort((short) s.length);
                buf.put(s);
            }
        }
    }

    private static WireEntry readEntry(ByteBuffer buf, SafetyLimits limits) {
        require(buf, 4 + 4 + 2, "entry header");
        int id = buf.getInt();
        int parentId = buf.getInt();
        int keyLen = Short.toUnsignedInt(buf.getShort());
        if (keyLen > limits.maxStringBytes()) {
            throw new WirepackException("key len exceeds limit");
        }
        require(buf, keyLen + 1, "key+kind");
        byte[] keyBytes = new byte[keyLen];
        buf.get(keyBytes);
        String key = new String(keyBytes, StandardCharsets.UTF_8);
        KvValueKind kind = KvValueKind.fromCode(Byte.toUnsignedInt(buf.get()));
        Object leaf = readLeaf(buf, kind, limits);
        return WireEntry.leaf(id, parentId, key, kind, leaf);
    }

    private static Object readLeaf(ByteBuffer buf, KvValueKind kind, SafetyLimits limits) {
        return switch (kind) {
            case NULL, OBJECT, ARRAY -> null;
            case BOOL -> {
                require(buf, 1, "bool");
                yield buf.get() != 0;
            }
            case I64 -> {
                require(buf, 8, "i64");
                yield buf.getLong();
            }
            case F64 -> {
                require(buf, 8, "f64");
                yield buf.getDouble();
            }
            case STRING -> {
                require(buf, 2, "string len");
                int len = Short.toUnsignedInt(buf.getShort());
                if (len > limits.maxStringBytes()) {
                    throw new WirepackException("string len exceeds limit");
                }
                require(buf, len, "string");
                byte[] s = new byte[len];
                buf.get(s);
                yield new String(s, StandardCharsets.UTF_8);
            }
            case BYTES -> {
                require(buf, 2, "bytes len");
                int len = Short.toUnsignedInt(buf.getShort());
                if (len > limits.maxStringBytes()) {
                    throw new WirepackException("bytes len exceeds limit");
                }
                require(buf, len, "bytes");
                byte[] s = new byte[len];
                buf.get(s);
                yield s;
            }
        };
    }

    private static void require(ByteBuffer buf, int n, String what) {
        if (buf.remaining() < n) {
            throw new WirepackException("truncated KV_TREE (" + what + ")");
        }
    }

    private record WireEntry(int id, int parentId, String key, KvValueKind kind, Object leaf) {
        static WireEntry leaf(int id, int parentId, String key, KvValueKind kind, Object leaf) {
            return new WireEntry(id, parentId, key, kind, leaf);
        }
    }
}
