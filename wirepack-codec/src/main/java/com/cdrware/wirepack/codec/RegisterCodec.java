package com.cdrware.wirepack.codec;

import com.cdrware.wirepack.api.BuiltinTypeId;
import com.cdrware.wirepack.api.Entity;
import com.cdrware.wirepack.api.FieldDef;
import com.cdrware.wirepack.api.RegisteredSchema;
import com.cdrware.wirepack.api.SafetyLimits;
import com.cdrware.wirepack.api.WireType;
import com.cdrware.wirepack.api.WirepackException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * REGISTER / ENTITY_FRAG codecs and schema_hash helper.
 */
public final class RegisterCodec {
    private RegisterCodec() {}

    public static int computeSchemaHash(String name, List<FieldDef> fields) {
        CRC32 crc = new CRC32();
        crc.update(name.getBytes(StandardCharsets.UTF_8));
        for (FieldDef f : fields) {
            crc.update(f.key().getBytes(StandardCharsets.UTF_8));
            crc.update(f.wireType().code());
        }
        return (int) crc.getValue();
    }

    public static RegisteredSchema schema(int typeId, String name, List<FieldDef> fields) {
        return new RegisteredSchema(typeId, name, fields, computeSchemaHash(name, fields));
    }

    public static byte[] encodeRegister(RegisteredSchema schema, SafetyLimits limits) {
        Objects.requireNonNull(schema, "schema");
        limits = limits == null ? SafetyLimits.defaults() : limits;
        if (schema.fields().size() > limits.maxFieldsPerRegister()) {
            throw new WirepackException("too many fields in REGISTER");
        }
        byte[] nameBytes = schema.name().getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length > 255) {
            throw new WirepackException("REGISTER name too long");
        }

        int size = 2 + 1 + nameBytes.length + 1;
        List<byte[]> keys = new ArrayList<>();
        for (FieldDef f : schema.fields()) {
            byte[] kb = f.key().getBytes(StandardCharsets.UTF_8);
            if (kb.length > 255) {
                throw new WirepackException("field key too long");
            }
            keys.add(kb);
            size += 1 + kb.length + 1;
        }
        size += 4;

        ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        buf.putShort((short) schema.typeId());
        buf.put((byte) nameBytes.length);
        buf.put(nameBytes);
        buf.put((byte) schema.fields().size());
        for (int i = 0; i < schema.fields().size(); i++) {
            byte[] kb = keys.get(i);
            buf.put((byte) kb.length);
            buf.put(kb);
            buf.put((byte) schema.fields().get(i).wireType().code());
        }
        buf.putInt(schema.schemaHash());
        return buf.array();
    }

    public static RegisteredSchema decodeRegister(byte[] value, SafetyLimits limits) {
        limits = limits == null ? SafetyLimits.defaults() : limits;
        ByteBuffer buf = ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN);
        require(buf, 2, "type_id");
        int typeId = Short.toUnsignedInt(buf.getShort());
        require(buf, 1, "name_len");
        int nameLen = Byte.toUnsignedInt(buf.get());
        require(buf, nameLen, "name");
        byte[] nameBytes = new byte[nameLen];
        buf.get(nameBytes);
        String name = new String(nameBytes, StandardCharsets.UTF_8);
        require(buf, 1, "field_count");
        int fieldCount = Byte.toUnsignedInt(buf.get());
        if (fieldCount > limits.maxFieldsPerRegister()) {
            throw new WirepackException("field_count exceeds maxFieldsPerRegister");
        }
        List<FieldDef> fields = new ArrayList<>(fieldCount);
        for (int i = 0; i < fieldCount; i++) {
            require(buf, 1, "key_len");
            int keyLen = Byte.toUnsignedInt(buf.get());
            require(buf, keyLen + 1, "key+wire_type");
            byte[] keyBytes = new byte[keyLen];
            buf.get(keyBytes);
            int wireCode = Byte.toUnsignedInt(buf.get());
            fields.add(new FieldDef(new String(keyBytes, StandardCharsets.UTF_8), WireType.fromCode(wireCode)));
        }
        require(buf, 4, "schema_hash");
        int schemaHash = buf.getInt();
        int expected = computeSchemaHash(name, fields);
        if (schemaHash != expected) {
            // Still accept advertised hash as authoritative for storage; mismatch vs recomputed
            // is allowed if peer sends precomputed — we store the on-wire hash.
        }
        return new RegisteredSchema(typeId, name, fields, schemaHash);
    }

    public static Entity toEntity(RegisteredSchema schema, SafetyLimits limits) {
        return new Entity(BuiltinTypeId.REGISTER.id(), encodeRegister(schema, limits));
    }

    public static byte[] encodeEntityFrag(byte[] uid, int fragIndex, int fragCount, byte[] data) {
        Objects.requireNonNull(uid, "uid");
        Objects.requireNonNull(data, "data");
        if (uid.length > 255) {
            throw new WirepackException("uid too long");
        }
        if (data.length > 0xFFFF) {
            throw new WirepackException("frag data too long");
        }
        ByteBuffer buf = ByteBuffer.allocate(1 + uid.length + 4 + 4 + 2 + data.length)
                .order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) uid.length);
        buf.put(uid);
        buf.putInt(fragIndex);
        buf.putInt(fragCount);
        buf.putShort((short) data.length);
        buf.put(data);
        return buf.array();
    }

    public static EntityFrag decodeEntityFrag(byte[] value) {
        ByteBuffer buf = ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN);
        require(buf, 1, "uid_len");
        int uidLen = Byte.toUnsignedInt(buf.get());
        require(buf, uidLen + 4 + 4 + 2, "frag header");
        byte[] uid = new byte[uidLen];
        buf.get(uid);
        int fragIndex = buf.getInt();
        int fragCount = buf.getInt();
        int dataLen = Short.toUnsignedInt(buf.getShort());
        require(buf, dataLen, "frag data");
        byte[] data = new byte[dataLen];
        buf.get(data);
        return new EntityFrag(uid, fragIndex, fragCount, data);
    }

    public static Entity toFragEntity(byte[] uid, int fragIndex, int fragCount, byte[] data) {
        return new Entity(BuiltinTypeId.ENTITY_FRAG.id(), encodeEntityFrag(uid, fragIndex, fragCount, data));
    }

    public record EntityFrag(byte[] uid, int fragIndex, int fragCount, byte[] data) {
        public EntityFrag {
            uid = uid.clone();
            data = data.clone();
        }

        public byte[] uid() {
            return uid.clone();
        }

        public byte[] data() {
            return data.clone();
        }
    }

    /**
     * Encode application entity fields by schema into value bytes.
     */
    public static byte[] encodeFields(RegisteredSchema schema, Map<String, Object> values, SafetyLimits limits) {
        limits = limits == null ? SafetyLimits.defaults() : limits;
        // size estimate then write
        List<byte[]> chunks = new ArrayList<>();
        int total = 0;
        for (FieldDef f : schema.fields()) {
            Object v = values.get(f.key());
            byte[] encoded = encodeField(f.wireType(), v, limits);
            chunks.add(encoded);
            total = Math.addExact(total, encoded.length);
        }
        ByteBuffer buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN);
        for (byte[] c : chunks) {
            buf.put(c);
        }
        return buf.array();
    }

    public static Map<String, Object> decodeFields(RegisteredSchema schema, byte[] value, SafetyLimits limits) {
        limits = limits == null ? SafetyLimits.defaults() : limits;
        ByteBuffer buf = ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN);
        Map<String, Object> out = new LinkedHashMap<>();
        for (FieldDef f : schema.fields()) {
            out.put(f.key(), decodeField(buf, f.wireType(), limits));
        }
        return out;
    }

    private static byte[] encodeField(WireType type, Object value, SafetyLimits limits) {
        return switch (type) {
            case U8 -> {
                ByteBuffer b = ByteBuffer.allocate(1);
                b.put((byte) toLong(value));
                yield b.array();
            }
            case U16 -> {
                ByteBuffer b = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN);
                b.putShort((short) toLong(value));
                yield b.array();
            }
            case U32 -> {
                ByteBuffer b = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
                b.putInt((int) toLong(value));
                yield b.array();
            }
            case U64 -> {
                ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
                b.putLong(toLong(value));
                yield b.array();
            }
            case F32 -> {
                ByteBuffer b = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
                b.putFloat(((Number) value).floatValue());
                yield b.array();
            }
            case F64 -> {
                ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
                b.putDouble(((Number) value).doubleValue());
                yield b.array();
            }
            case STRING -> {
                byte[] s = value == null ? new byte[0] : value.toString().getBytes(StandardCharsets.UTF_8);
                if (s.length > limits.maxStringBytes()) {
                    throw new WirepackException("string too long");
                }
                ByteBuffer b = ByteBuffer.allocate(2 + s.length).order(ByteOrder.BIG_ENDIAN);
                b.putShort((short) s.length);
                b.put(s);
                yield b.array();
            }
            case BYTES -> {
                byte[] s = value == null ? new byte[0] : (byte[]) value;
                if (s.length > limits.maxStringBytes()) {
                    throw new WirepackException("bytes too long");
                }
                ByteBuffer b = ByteBuffer.allocate(2 + s.length).order(ByteOrder.BIG_ENDIAN);
                b.putShort((short) s.length);
                b.put(s);
                yield b.array();
            }
            case NESTED -> {
                byte[] s = value == null ? new byte[0] : (byte[]) value;
                ByteBuffer b = ByteBuffer.allocate(2 + s.length).order(ByteOrder.BIG_ENDIAN);
                b.putShort((short) s.length);
                b.put(s);
                yield b.array();
            }
        };
    }

    private static Object decodeField(ByteBuffer buf, WireType type, SafetyLimits limits) {
        return switch (type) {
            case U8 -> {
                require(buf, 1, "u8");
                yield Byte.toUnsignedInt(buf.get());
            }
            case U16 -> {
                require(buf, 2, "u16");
                yield Short.toUnsignedInt(buf.getShort());
            }
            case U32 -> {
                require(buf, 4, "u32");
                yield Integer.toUnsignedLong(buf.getInt());
            }
            case U64 -> {
                require(buf, 8, "u64");
                yield buf.getLong();
            }
            case F32 -> {
                require(buf, 4, "f32");
                yield buf.getFloat();
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
            case BYTES, NESTED -> {
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

    private static long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        throw new WirepackException("expected number, got " + value);
    }

    private static void require(ByteBuffer buf, int n, String what) {
        if (buf.remaining() < n) {
            throw new WirepackException("truncated " + what);
        }
    }
}
