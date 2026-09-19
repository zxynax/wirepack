package com.cdrware.wirepack.api;

/**
 * Field wire types for REGISTER schemas.
 */
public enum WireType {
    U8(1, 1),
    U16(2, 2),
    U32(3, 4),
    U64(4, 8),
    F32(5, 4),
    F64(6, 8),
    STRING(7, -1),
    BYTES(8, -1),
    NESTED(9, -1);

    private final int code;
    /** Fixed on-wire size, or -1 if length-prefixed. */
    private final int fixedSize;

    WireType(int code, int fixedSize) {
        this.code = code;
        this.fixedSize = fixedSize;
    }

    public int code() {
        return code;
    }

    public int fixedSize() {
        return fixedSize;
    }

    public boolean isLengthPrefixed() {
        return fixedSize < 0;
    }

    public static WireType fromCode(int code) {
        return switch (code) {
            case 1 -> U8;
            case 2 -> U16;
            case 3 -> U32;
            case 4 -> U64;
            case 5 -> F32;
            case 6 -> F64;
            case 7 -> STRING;
            case 8 -> BYTES;
            case 9 -> NESTED;
            default -> throw new IllegalArgumentException("Unknown wire_type: " + code);
        };
    }
}
