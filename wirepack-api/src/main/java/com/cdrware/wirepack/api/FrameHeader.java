package com.cdrware.wirepack.api;

import java.util.Objects;

/**
 * Decoded cleartext frame header fields (before payload / crc).
 */
public record FrameHeader(
        int version,
        int keyId,
        MsgType msgType,
        int sessionId,
        int streamId,
        int seq,
        int lastRcv,
        int payloadLen
) {
    public static final int MAGIC = 0x5750;
    public static final int VERSION_V0 = 0;
    /** Bytes before payload: magic..payloadLen (sum of wire fields). */
    public static final int HEADER_LEN = 20;
    public static final int CRC_LEN = 4;
    /** Reject if HEADER_LEN + CRC_LEN + payloadLen > maxFrameBytes. */
    public static final int PROTOCOL_OVERHEAD = HEADER_LEN + CRC_LEN;
    public static final int WIRE_OVERHEAD = PROTOCOL_OVERHEAD;

    public FrameHeader {
        Objects.requireNonNull(msgType, "msgType");
        if (version < 0 || version > 0xFF) {
            throw new IllegalArgumentException("version out of range");
        }
        if (streamId < 0 || streamId > 0xFFFF) {
            throw new IllegalArgumentException("streamId out of range");
        }
        if (seq < 0 || seq > 0xFFFF) {
            throw new IllegalArgumentException("seq out of range");
        }
        if (lastRcv < 0 || lastRcv > 0xFFFF) {
            throw new IllegalArgumentException("lastRcv out of range");
        }
        if (payloadLen < 0 || payloadLen > 0xFFFF) {
            throw new IllegalArgumentException("payloadLen out of range");
        }
    }
}
