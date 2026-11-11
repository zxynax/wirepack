package com.cdrware.wirepack.codec;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.cdrware.wirepack.api.FrameHeader;
import com.cdrware.wirepack.api.SafetyLimits;
import com.cdrware.wirepack.api.WirepackException;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Release gate for the frame and entity decoders. Named cases cover the reject
 * paths. The fuzz methods run under Jazzer during {@code mvn test}; a crash or
 * an allocation past {@link SafetyLimits} fails the build.
 */
class FrameDecoderFuzzTest {

    private final SafetyLimits limits = SafetyLimits.defaults();
    private final FrameCodec frames = new FrameCodec(limits);

    @Test
    void rejectsBadMagic() {
        assertThrows(WirepackException.class, () -> frames.decode(new byte[FrameHeader.WIRE_OVERHEAD]));
    }

    @Test
    void rejectsBadVersion() {
        byte[] data = blankFrame();
        data[2] = 9;
        assertThrows(WirepackException.class, () -> frames.decode(data));
    }

    @Test
    void rejectsTruncatedFrame() {
        byte[] data = new byte[]{0x57, 0x50, 0};
        assertThrows(WirepackException.class, () -> frames.decode(data));
    }

    @Test
    void rejectsTruncatedPayload() {
        byte[] data = blankFrame();
        data[7] = 1;
        data[19] = 8;
        assertThrows(WirepackException.class, () -> frames.decode(data));
    }

    @Test
    void rejectsOversizePayloadLen() {
        byte[] data = blankFrame();
        data[7] = 1;
        data[18] = (byte) 0xFF;
        data[19] = (byte) 0xFF;
        assertThrows(WirepackException.class, () -> frames.decode(data));
    }

    @Test
    void rejectsTruncatedEntity() {
        assertThrows(WirepackException.class, () -> new EntityReader(new byte[]{0, 1}, limits).readAll());
    }

    @Test
    void rejectsOversizeEntityLength() {
        SafetyLimits tight = SafetyLimits.builder().maxStringBytes(4).build();
        byte[] payload = new byte[]{0, 1, 0, 5};
        assertThrows(WirepackException.class, () -> new EntityReader(payload, tight).readAll());
    }

    @Test
    void rejectsHostileArrayIndex() {
        assertThrows(WirepackException.class, () -> KvTreeCodec.decode(hostileArrayIndex(), limits));
    }

    /**
     * One campaign: Jazzer fuzzes a single {@code @FuzzTest} per JVM. The first
     * byte picks the decoder; the rest is the input.
     */
    @FuzzTest(maxDuration = "20s")
    void fuzzDecoders(byte[] data) {
        if (data.length == 0) {
            return;
        }
        byte[] body = Arrays.copyOfRange(data, 1, data.length);
        switch (data[0] & 3) {
            case 0 -> accept(() -> frames.decode(body));
            case 1 -> {
                if (body.length > limits.maxPayloadBytes()) {
                    return;
                }
                accept(() -> new EntityReader(body, limits).readAll());
            }
            default -> accept(() -> KvTreeCodec.decode(body, limits));
        }
    }

    private static void accept(Runnable decode) {
        try {
            decode.run();
        } catch (WirepackException expected) {
            // SafetyLimits or a malformed field. Anything else fails the run.
        }
    }

    private static byte[] blankFrame() {
        byte[] data = new byte[FrameHeader.WIRE_OVERHEAD];
        data[0] = 0x57;
        data[1] = 0x50;
        return data;
    }

    /** One array whose only child names an index far past {@code maxKvEntries}. */
    private static byte[] hostileArrayIndex() {
        byte[] key = "a".getBytes(StandardCharsets.UTF_8);
        byte[] index = "999999".getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(4 + entrySize(key) + entrySize(index)).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(2);
        putEntry(buf, 1, 0, key, 7);
        putEntry(buf, 2, 1, index, 0);
        return buf.array();
    }

    private static int entrySize(byte[] key) {
        return 4 + 4 + 2 + key.length + 1;
    }

    private static void putEntry(ByteBuffer buf, int id, int parentId, byte[] key, int kind) {
        buf.putInt(id);
        buf.putInt(parentId);
        buf.putShort((short) key.length);
        buf.put(key);
        buf.put((byte) kind);
    }
}
