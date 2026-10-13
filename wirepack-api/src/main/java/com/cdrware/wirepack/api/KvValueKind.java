package com.cdrware.wirepack.api;

/**
 * Value kinds inside a {@link BuiltinTypeId#KV_TREE} entry (PROTOCOL.md).
 */
public enum KvValueKind {
    NULL(0),
    BOOL(1),
    I64(2),
    F64(3),
    STRING(4),
    BYTES(5),
    OBJECT(6),
    ARRAY(7);

    private final int code;

    KvValueKind(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static KvValueKind fromCode(int code) {
        return switch (code) {
            case 0 -> NULL;
            case 1 -> BOOL;
            case 2 -> I64;
            case 3 -> F64;
            case 4 -> STRING;
            case 5 -> BYTES;
            case 6 -> OBJECT;
            case 7 -> ARRAY;
            default -> throw new IllegalArgumentException("Unknown KvValueKind: " + code);
        };
    }
}
