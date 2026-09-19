package com.cdrware.wirepack.codec;

import com.cdrware.wirepack.api.FrameHeader;
import com.cdrware.wirepack.api.MsgType;
import com.cdrware.wirepack.api.SafetyLimits;
import com.cdrware.wirepack.api.WirepackException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * Encode/decode cleartext wirepack frames with CRC32 integrity.
 */
public final class FrameCodec {
    private final SafetyLimits limits;

    public FrameCodec(SafetyLimits limits) {
        this.limits = limits == null ? SafetyLimits.defaults() : limits;
    }

    public FrameCodec() {
        this(SafetyLimits.defaults());
    }

    public SafetyLimits limits() {
        return limits;
    }

    public byte[] encode(FrameHeader header, byte[] payload) {
        if (payload == null) {
            payload = new byte[0];
        }
        if (payload.length != header.payloadLen()) {
            throw new WirepackException("payload length mismatch: header="
                    + header.payloadLen() + " actual=" + payload.length);
        }
        rejectOversized(header.payloadLen());

        byte[] frame = new byte[FrameHeader.HEADER_LEN + payload.length + FrameHeader.CRC_LEN];
        ByteBuffer buf = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        writeHeader(buf, header);
        buf.put(payload);
        int crc = crc32(frame, 0, FrameHeader.HEADER_LEN + payload.length);
        buf.putInt(crc);
        return frame;
    }

    public DecodedFrame decode(byte[] data) {
        return decode(data, 0, data.length);
    }

    public DecodedFrame decode(byte[] data, int offset, int length) {
        if (data == null) {
            throw new WirepackException("null frame");
        }
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new WirepackException("frame bounds invalid");
        }
        if (length < FrameHeader.WIRE_OVERHEAD) {
            throw new WirepackException("frame too short: " + length);
        }
        if (length > limits.maxFrameBytes()) {
            throw new WirepackException("frame exceeds maxFrameBytes: " + length
                    + " > " + limits.maxFrameBytes());
        }

        ByteBuffer buf = ByteBuffer.wrap(data, offset, length).order(ByteOrder.BIG_ENDIAN);
        int magic = Short.toUnsignedInt(buf.getShort());
        if (magic != FrameHeader.MAGIC) {
            throw new WirepackException("bad magic: 0x" + Integer.toHexString(magic));
        }
        int version = Byte.toUnsignedInt(buf.get());
        if (version != FrameHeader.VERSION_V0) {
            throw new WirepackException("unsupported version: " + version);
        }
        int keyId = buf.getInt();
        int msgTypeCode = Byte.toUnsignedInt(buf.get());
        MsgType msgType = MsgType.fromCode(msgTypeCode);
        int sessionId = buf.getInt();
        int streamId = Short.toUnsignedInt(buf.getShort());
        int seq = Short.toUnsignedInt(buf.getShort());
        int lastRcv = Short.toUnsignedInt(buf.getShort());
        int payloadLen = Short.toUnsignedInt(buf.getShort());

        rejectOversized(payloadLen);

        int expectedTotal = FrameHeader.HEADER_LEN + payloadLen + FrameHeader.CRC_LEN;
        if (length < expectedTotal) {
            throw new WirepackException("truncated frame: need " + expectedTotal + " have " + length);
        }
        // PROTOCOL: reject if 24 + payloadLen > maxFrameBytes before allocating payload
        if (FrameHeader.PROTOCOL_OVERHEAD + payloadLen > limits.maxFrameBytes()) {
            throw new WirepackException("oversized by protocol overhead check");
        }

        byte[] payload = new byte[payloadLen];
        buf.get(payload);
        int claimedCrc = buf.getInt();

        // CRC over header (with crc region not present — crc is after payload) + payload
        int computed = crc32(data, offset, FrameHeader.HEADER_LEN + payloadLen);
        if (computed != claimedCrc) {
            throw new WirepackException("CRC mismatch: claimed=0x" + Integer.toHexString(claimedCrc)
                    + " computed=0x" + Integer.toHexString(computed));
        }

        FrameHeader header = new FrameHeader(
                version, keyId, msgType, sessionId, streamId, seq, lastRcv, payloadLen);
        return new DecodedFrame(header, payload);
    }

    private void rejectOversized(int payloadLen) {
        if (payloadLen > limits.maxPayloadBytes()) {
            throw new WirepackException("payloadLen " + payloadLen
                    + " exceeds maxPayloadBytes " + limits.maxPayloadBytes());
        }
        if (FrameHeader.PROTOCOL_OVERHEAD + payloadLen > limits.maxFrameBytes()) {
            throw new WirepackException("24 + payloadLen exceeds maxFrameBytes");
        }
        long total = (long) FrameHeader.HEADER_LEN + payloadLen + FrameHeader.CRC_LEN;
        if (total > limits.maxFrameBytes()) {
            throw new WirepackException("frame size " + total + " exceeds maxFrameBytes");
        }
    }

    public static byte[] headerBytes(FrameHeader header) {
        byte[] out = new byte[FrameHeader.HEADER_LEN];
        ByteBuffer buf = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN);
        writeHeader(buf, header);
        return out;
    }

    private static void writeHeader(ByteBuffer buf, FrameHeader header) {
        buf.putShort((short) FrameHeader.MAGIC);
        buf.put((byte) header.version());
        buf.putInt(header.keyId());
        buf.put((byte) header.msgType().code());
        buf.putInt(header.sessionId());
        buf.putShort((short) header.streamId());
        buf.putShort((short) header.seq());
        buf.putShort((short) header.lastRcv());
        buf.putShort((short) header.payloadLen());
    }

    public static int crc32(byte[] data, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(data, offset, length);
        return (int) crc.getValue();
    }

    public record DecodedFrame(FrameHeader header, byte[] payload) {
        public DecodedFrame {
            payload = payload == null ? new byte[0] : payload.clone();
        }

        public byte[] payload() {
            return payload.clone();
        }

        public byte[] payloadRaw() {
            return payload;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DecodedFrame that)) return false;
            return header.equals(that.header) && Arrays.equals(payload, that.payload);
        }

        @Override
        public int hashCode() {
            return 31 * header.hashCode() + Arrays.hashCode(payload);
        }
    }
}
