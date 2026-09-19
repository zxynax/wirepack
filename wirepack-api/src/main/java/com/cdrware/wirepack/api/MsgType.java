package com.cdrware.wirepack.api;

/**
 * Frame message types (PROTOCOL.md msgType).
 */
public enum MsgType {
    DATA(1),
    ACK(2),
    REQ(3),
    HELLO(4),
    BYE(5);

    private final int code;

    MsgType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static MsgType fromCode(int code) {
        return switch (code) {
            case 1 -> DATA;
            case 2 -> ACK;
            case 3 -> REQ;
            case 4 -> HELLO;
            case 5 -> BYE;
            default -> throw new IllegalArgumentException("Unknown msgType: " + code);
        };
    }

    public boolean consumesSeq() {
        return this == DATA;
    }
}
