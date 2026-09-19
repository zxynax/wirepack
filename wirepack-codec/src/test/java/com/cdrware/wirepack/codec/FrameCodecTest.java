package com.cdrware.wirepack.codec;

import com.cdrware.wirepack.api.FrameHeader;
import com.cdrware.wirepack.api.MsgType;
import com.cdrware.wirepack.api.SafetyLimits;
import com.cdrware.wirepack.api.WirepackException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FrameCodecTest {

    private final SafetyLimits limits = SafetyLimits.defaults();
    private final FrameCodec codec = new FrameCodec(limits);

    @Test
    void roundtripFrame() {
        byte[] payload = new byte[]{1, 2, 3, 4, 5};
        FrameHeader header = new FrameHeader(
                FrameHeader.VERSION_V0, 7, MsgType.DATA, 42, 1, 10, 9, payload.length);
        byte[] encoded = codec.encode(header, payload);
        FrameCodec.DecodedFrame decoded = codec.decode(encoded);
        assertEquals(header, decoded.header());
        assertArrayEquals(payload, decoded.payloadRaw());
    }

    @Test
    void rejectOversizedFrame() {
        SafetyLimits tight = SafetyLimits.builder()
                .maxFrameBytes(100)
                .maxPayloadBytes(50)
                .build();
        FrameCodec tightCodec = new FrameCodec(tight);
        byte[] big = new byte[60];
        FrameHeader header = new FrameHeader(
                FrameHeader.VERSION_V0, 0, MsgType.DATA, 1, 0, 0, 0, big.length);
        assertThrows(WirepackException.class, () -> tightCodec.encode(header, big));

        // Craft a frame that claims large payloadLen
        byte[] fake = new byte[FrameHeader.WIRE_OVERHEAD];
        // magic
        fake[0] = 0x57;
        fake[1] = 0x50;
        fake[2] = 0; // version
        // keyId 3-6 = 0
        fake[7] = 1; // DATA
        // sessionId, streamId, seq, lastRcv zeros
        // payloadLen at offset 18-19
        fake[18] = 0x05;
        fake[19] = 0x00; // 1280
        assertThrows(WirepackException.class, () -> tightCodec.decode(fake));
    }
}
