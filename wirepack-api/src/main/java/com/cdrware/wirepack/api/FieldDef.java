package com.cdrware.wirepack.api;

import java.util.Objects;

/**
 * One field in a REGISTER schema.
 */
public record FieldDef(String key, WireType wireType) {
    public FieldDef {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(wireType, "wireType");
        if (key.isEmpty()) {
            throw new IllegalArgumentException("field key must not be empty");
        }
    }
}
