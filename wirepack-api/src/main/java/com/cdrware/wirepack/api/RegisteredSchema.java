package com.cdrware.wirepack.api;

import java.util.List;
import java.util.Objects;

/**
 * Schema registered under a type_id with schema_hash for conflict detection.
 */
public record RegisteredSchema(
        int typeId,
        String name,
        List<FieldDef> fields,
        int schemaHash
) {
    public RegisteredSchema {
        if (typeId < 0 || typeId > 0xFFFF) {
            throw new IllegalArgumentException("typeId out of u16 range");
        }
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(fields, "fields");
        fields = List.copyOf(fields);
    }
}
