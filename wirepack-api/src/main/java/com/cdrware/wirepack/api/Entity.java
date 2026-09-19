package com.cdrware.wirepack.api;

import java.util.Objects;

/**
 * One TLV entity: type_id + value bytes (length implied by value length).
 */
public record Entity(int typeId, byte[] value) {
    public Entity {
        if (typeId < 0 || typeId > 0xFFFF) {
            throw new IllegalArgumentException("typeId out of u16 range");
        }
        Objects.requireNonNull(value, "value");
        if (value.length > 0xFFFF) {
            throw new IllegalArgumentException("entity value exceeds u16 length");
        }
        value = value.clone();
    }

    public int length() {
        return value.length;
    }

    public byte[] value() {
        return value.clone();
    }

    /** Raw value without cloning — caller must not mutate. */
    public byte[] valueRaw() {
        return value;
    }

    public static Entity eoe() {
        return new Entity(BuiltinTypeId.EOE.id(), new byte[0]);
    }
}
