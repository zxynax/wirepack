package com.cdrware.wirepack.api;

/**
 * Answer to a {@code REGISTER}: the type id, the schema hash, and whether it was stored.
 *
 * <p>{@code accepted} is false when that type id is already stored under a different hash.
 * The receiver then quarantines the type and leaves the stored schema unchanged.
 */
public record RegisterAck(int typeId, int schemaHash, boolean accepted) {
    public RegisterAck {
        if (typeId < 0 || typeId > 0xFFFF) {
            throw new IllegalArgumentException("typeId out of u16 range");
        }
    }
}
