package com.cdrware.wirepack.api;

/**
 * Hard caps for decoding, buffers, sessions, and v1 rekey cadence (PROTOCOL.md).
 */
public record SafetyLimits(
        int maxFrameBytes,
        int maxPayloadBytes,
        int maxKvDepth,
        int maxNestedDepth,
        int maxEntitiesPerFrame,
        int maxFieldsPerRegister,
        int maxStringBytes,
        int maxRetransmitEntries,
        int maxRetransmitBytes,
        int maxSessions,
        long sessionIdleMs,
        int maxActivateBodyBytes,
        int rekeyAfterPackets
) {
    public static final int DEFAULT_MAX_FRAME_BYTES = 1400;
    /** maxFrame − header(20) − crc(4) */
    public static final int DEFAULT_MAX_PAYLOAD_BYTES = 1376;
    public static final int DEFAULT_MAX_KV_DEPTH = 8;
    public static final int DEFAULT_MAX_NESTED_DEPTH = 8;
    public static final int DEFAULT_MAX_ENTITIES_PER_FRAME = 256;
    public static final int DEFAULT_MAX_FIELDS_PER_REGISTER = 64;
    public static final int DEFAULT_MAX_STRING_BYTES = 65535;
    public static final int DEFAULT_MAX_RETRANSMIT_ENTRIES = 2048;
    public static final int DEFAULT_MAX_RETRANSMIT_BYTES = 4 * 1024 * 1024;
    public static final int DEFAULT_MAX_SESSIONS = 256;
    public static final long DEFAULT_SESSION_IDLE_MS = 120_000L;
    public static final int DEFAULT_MAX_ACTIVATE_BODY_BYTES = 65536;
    public static final int DEFAULT_WINDOW_SIZE = 2048;
    /** DATA packets sealed under current keyId before REKEY_OFFER. */
    public static final int DEFAULT_REKEY_AFTER_PACKETS = 10_000;

    public SafetyLimits {
        if (maxFrameBytes < FrameHeader.PROTOCOL_OVERHEAD) {
            throw new IllegalArgumentException(
                    "maxFrameBytes must be >= " + FrameHeader.PROTOCOL_OVERHEAD);
        }
        if (maxPayloadBytes < 0 || maxPayloadBytes > maxFrameBytes) {
            throw new IllegalArgumentException("maxPayloadBytes out of range");
        }
        if (rekeyAfterPackets < 1) {
            throw new IllegalArgumentException("rekeyAfterPackets must be >= 1");
        }
    }

    public static SafetyLimits defaults() {
        return new SafetyLimits(
                DEFAULT_MAX_FRAME_BYTES,
                DEFAULT_MAX_PAYLOAD_BYTES,
                DEFAULT_MAX_KV_DEPTH,
                DEFAULT_MAX_NESTED_DEPTH,
                DEFAULT_MAX_ENTITIES_PER_FRAME,
                DEFAULT_MAX_FIELDS_PER_REGISTER,
                DEFAULT_MAX_STRING_BYTES,
                DEFAULT_MAX_RETRANSMIT_ENTRIES,
                DEFAULT_MAX_RETRANSMIT_BYTES,
                DEFAULT_MAX_SESSIONS,
                DEFAULT_SESSION_IDLE_MS,
                DEFAULT_MAX_ACTIVATE_BODY_BYTES,
                DEFAULT_REKEY_AFTER_PACKETS
        );
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static Builder builder() {
        return new Builder(defaults());
    }

    public static final class Builder {
        private int maxFrameBytes;
        private int maxPayloadBytes;
        private int maxKvDepth;
        private int maxNestedDepth;
        private int maxEntitiesPerFrame;
        private int maxFieldsPerRegister;
        private int maxStringBytes;
        private int maxRetransmitEntries;
        private int maxRetransmitBytes;
        private int maxSessions;
        private long sessionIdleMs;
        private int maxActivateBodyBytes;
        private int rekeyAfterPackets;

        private Builder(SafetyLimits base) {
            this.maxFrameBytes = base.maxFrameBytes;
            this.maxPayloadBytes = base.maxPayloadBytes;
            this.maxKvDepth = base.maxKvDepth;
            this.maxNestedDepth = base.maxNestedDepth;
            this.maxEntitiesPerFrame = base.maxEntitiesPerFrame;
            this.maxFieldsPerRegister = base.maxFieldsPerRegister;
            this.maxStringBytes = base.maxStringBytes;
            this.maxRetransmitEntries = base.maxRetransmitEntries;
            this.maxRetransmitBytes = base.maxRetransmitBytes;
            this.maxSessions = base.maxSessions;
            this.sessionIdleMs = base.sessionIdleMs;
            this.maxActivateBodyBytes = base.maxActivateBodyBytes;
            this.rekeyAfterPackets = base.rekeyAfterPackets;
        }

        public Builder maxFrameBytes(int v) { this.maxFrameBytes = v; return this; }
        public Builder maxPayloadBytes(int v) { this.maxPayloadBytes = v; return this; }
        public Builder maxKvDepth(int v) { this.maxKvDepth = v; return this; }
        public Builder maxNestedDepth(int v) { this.maxNestedDepth = v; return this; }
        public Builder maxEntitiesPerFrame(int v) { this.maxEntitiesPerFrame = v; return this; }
        public Builder maxFieldsPerRegister(int v) { this.maxFieldsPerRegister = v; return this; }
        public Builder maxStringBytes(int v) { this.maxStringBytes = v; return this; }
        public Builder maxRetransmitEntries(int v) { this.maxRetransmitEntries = v; return this; }
        public Builder maxRetransmitBytes(int v) { this.maxRetransmitBytes = v; return this; }
        public Builder maxSessions(int v) { this.maxSessions = v; return this; }
        public Builder sessionIdleMs(long v) { this.sessionIdleMs = v; return this; }
        public Builder maxActivateBodyBytes(int v) { this.maxActivateBodyBytes = v; return this; }
        public Builder rekeyAfterPackets(int v) { this.rekeyAfterPackets = v; return this; }

        public SafetyLimits build() {
            return new SafetyLimits(
                    maxFrameBytes, maxPayloadBytes, maxKvDepth, maxNestedDepth,
                    maxEntitiesPerFrame, maxFieldsPerRegister, maxStringBytes,
                    maxRetransmitEntries, maxRetransmitBytes, maxSessions,
                    sessionIdleMs, maxActivateBodyBytes, rekeyAfterPackets
            );
        }
    }
}
