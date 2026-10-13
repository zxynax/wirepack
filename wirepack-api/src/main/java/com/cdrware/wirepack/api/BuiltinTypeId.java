package com.cdrware.wirepack.api;

    /**
     * Built-in entity type_ids (0–63). Application types start at 1024.
     *
     * <p>{@link #REKEY_OFFER} / {@link #REKEY_ACCEPT} are reserved for v1 in-flight ECDH
     * rekey (see PROTOCOL.md). {@link #KV_TREE} carries free-form nested maps via parentId K/V.
     */
public enum BuiltinTypeId {
    EOE(0),
    REGISTER(1),
    REGISTER_ACK(2),
    ENTITY_FRAG(3),
    /** v1: piggybacked X25519 rekey offer. */
    REKEY_OFFER(4),
    /** v1: piggybacked X25519 rekey accept. */
    REKEY_ACCEPT(5),
    /** Free-form nested object tree (parentId K/V). */
    KV_TREE(6);

    public static final int APPLICATION_TYPE_MIN = 1024;

    private final int id;

    BuiltinTypeId(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static BuiltinTypeId fromId(int id) {
        return switch (id) {
            case 0 -> EOE;
            case 1 -> REGISTER;
            case 2 -> REGISTER_ACK;
            case 3 -> ENTITY_FRAG;
            case 4 -> REKEY_OFFER;
            case 5 -> REKEY_ACCEPT;
            case 6 -> KV_TREE;
            default -> throw new IllegalArgumentException("Unknown builtin type_id: " + id);
        };
    }

    public static boolean isBuiltin(int typeId) {
        return typeId >= 0 && typeId <= 63;
    }

    public static boolean isApplication(int typeId) {
        return typeId >= APPLICATION_TYPE_MIN;
    }
}
