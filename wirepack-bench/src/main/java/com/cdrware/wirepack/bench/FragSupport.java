package com.cdrware.wirepack.bench;

import com.cdrware.wirepack.api.BuiltinTypeId;
import com.cdrware.wirepack.api.Entity;
import com.cdrware.wirepack.api.SafetyLimits;
import com.cdrware.wirepack.codec.RegisterCodec;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Bench-only logical-entity fragmentation using builtin {@code ENTITY_FRAG}.
 * Keeps reassembly out of wirepack-core.
 */
final class FragSupport {
    private FragSupport() {
    }

    /** Bytes of frag value overhead for a given uid length (excludes TLV type/len). */
    static int fragValueOverhead(int uidLen) {
        return 1 + uidLen + 4 + 4 + 2;
    }

    /**
     * Max data bytes per frag entity so the whole TLV fits in {@code maxPayloadBytes},
     * optionally reserving AEAD overhead (nonce+tag) when traffic is sealed.
     */
    static int maxFragDataBytes(SafetyLimits limits, int uidLen, int aeadOverhead) {
        int tlvHeader = 4; // type_id u16 + length u16
        int budget = limits.maxPayloadBytes() - aeadOverhead - tlvHeader - fragValueOverhead(uidLen);
        if (budget < 1) {
            throw new IllegalArgumentException("maxPayloadBytes too small for ENTITY_FRAG");
        }
        return Math.min(budget, 0xFFFF);
    }

    static int maxFragDataBytes(SafetyLimits limits, int uidLen) {
        return maxFragDataBytes(limits, uidLen, 0);
    }

    static byte[] uidFor(int entityIndex) {
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.putInt(0x42574e43); // "BWNC" bench magic
        buf.putInt(entityIndex);
        return buf.array();
    }

    static List<Entity> toWireEntities(
            int entityIndex, byte[] payload, SafetyLimits limits, int aeadOverhead) {
        int maxData = maxFragDataBytes(limits, 8, aeadOverhead);
        if (payload.length <= maxData) {
            return List.of(new Entity(BenchPayloads.TYPE_ID, payload));
        }
        byte[] uid = uidFor(entityIndex);
        int fragCount = (payload.length + maxData - 1) / maxData;
        List<Entity> frags = new ArrayList<>(fragCount);
        for (int i = 0; i < fragCount; i++) {
            int from = i * maxData;
            int to = Math.min(payload.length, from + maxData);
            byte[] slice = Arrays.copyOfRange(payload, from, to);
            frags.add(RegisterCodec.toFragEntity(uid, i, fragCount, slice));
        }
        return frags;
    }

    static List<Entity> toWireEntities(int entityIndex, byte[] payload, SafetyLimits limits) {
        return toWireEntities(entityIndex, payload, limits, 0);
    }

    /**
     * Counts complete logical entities: either {@link BenchPayloads#TYPE_ID} wholes
     * or fully reassembled {@code ENTITY_FRAG} sets.
     */
    static final class Reassembler implements Consumer<Entity> {
        private final Consumer<Integer> onComplete;
        private final Map<Long, Partial> partials = new HashMap<>();
        private int completed;

        Reassembler(Consumer<Integer> onComplete) {
            this.onComplete = onComplete;
        }

        int completed() {
            return completed;
        }

        @Override
        public void accept(Entity e) {
            if (e.typeId() == BenchPayloads.TYPE_ID) {
                completed++;
                onComplete.accept(completed);
                return;
            }
            if (e.typeId() != BuiltinTypeId.ENTITY_FRAG.id()) {
                return;
            }
            RegisterCodec.EntityFrag frag = RegisterCodec.decodeEntityFrag(e.valueRaw());
            long key = uidKey(frag.uid());
            Partial p = partials.computeIfAbsent(key, k -> new Partial(frag.fragCount()));
            if (p.fragCount != frag.fragCount()) {
                throw new IllegalStateException("fragCount mismatch for uid");
            }
            if (frag.fragIndex() < 0 || frag.fragIndex() >= p.fragCount) {
                throw new IllegalStateException("fragIndex out of range");
            }
            if (p.parts[frag.fragIndex()] != null) {
                return; // duplicate
            }
            p.parts[frag.fragIndex()] = frag.data();
            p.got++;
            if (p.got == p.fragCount) {
                partials.remove(key);
                completed++;
                onComplete.accept(completed);
            }
        }

        private static long uidKey(byte[] uid) {
            long h = 0xcbf29ce484222325L;
            for (byte b : uid) {
                h ^= Byte.toUnsignedLong(b);
                h *= 0x100000001b3L;
            }
            return h;
        }

        private static final class Partial {
            final int fragCount;
            final byte[][] parts;
            int got;

            Partial(int fragCount) {
                this.fragCount = fragCount;
                this.parts = new byte[fragCount][];
            }
        }
    }
}
