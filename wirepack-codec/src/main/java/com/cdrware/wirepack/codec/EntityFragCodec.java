package com.cdrware.wirepack.codec;

import com.cdrware.wirepack.api.BuiltinTypeId;
import com.cdrware.wirepack.api.Entity;
import com.cdrware.wirepack.api.SafetyLimits;
import com.cdrware.wirepack.api.WirepackException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Split / reassemble logical entity values via builtin {@link BuiltinTypeId#ENTITY_FRAG}.
 *
 * <p>UID layout (14 bytes): {@code magic:u32=0x57504652} ({@code WPFR}) + {@code typeId:u16} +
 * {@code random:u64}. Reassembly yields {@code Entity(typeId, concatenatedData)}.
 */
public final class EntityFragCodec {
    public static final int UID_MAGIC = 0x57504652; // WPFR
    public static final int UID_LEN = 14;

    private static final SecureRandom RND = new SecureRandom();

    private EntityFragCodec() {}

    public static int fragValueOverhead(int uidLen) {
        return 1 + uidLen + 4 + 4 + 2;
    }

    public static int maxFragDataBytes(SafetyLimits limits, int uidLen, int aeadOverhead) {
        limits = limits == null ? SafetyLimits.defaults() : limits;
        int tlvHeader = 4;
        int budget = limits.maxPayloadBytes() - aeadOverhead - tlvHeader - fragValueOverhead(uidLen);
        if (budget < 1) {
            throw new WirepackException("maxPayloadBytes too small for ENTITY_FRAG");
        }
        return Math.min(budget, 0xFFFF);
    }

    /** Build wire entities: single TLV when it fits, else ENTITY_FRAG slices. */
    public static List<Entity> toWireEntities(
            int logicalTypeId, byte[] value, SafetyLimits limits, int aeadOverhead) {
        Objects.requireNonNull(value, "value");
        limits = limits == null ? SafetyLimits.defaults() : limits;
        int maxPlain = Math.max(0, limits.maxPayloadBytes() - aeadOverhead);
        int maxSingleValue = Math.min(0xFFFF, maxPlain - 4);
        if (maxSingleValue > 0 && value.length <= maxSingleValue) {
            return List.of(new Entity(logicalTypeId, value));
        }
        byte[] uid = newUid(logicalTypeId);
        int maxData = maxFragDataBytes(limits, uid.length, aeadOverhead);
        int fragCount = (value.length + maxData - 1) / maxData;
        if (fragCount <= 0) {
            throw new WirepackException("cannot fragment empty oversized budget");
        }
        List<Entity> frags = new ArrayList<>(fragCount);
        for (int i = 0; i < fragCount; i++) {
            int from = i * maxData;
            int to = Math.min(value.length, from + maxData);
            byte[] slice = Arrays.copyOfRange(value, from, to);
            frags.add(RegisterCodec.toFragEntity(uid, i, fragCount, slice));
        }
        return frags;
    }

    public static List<Entity> toWireEntities(int logicalTypeId, byte[] value, SafetyLimits limits) {
        return toWireEntities(logicalTypeId, value, limits, 0);
    }

    public static byte[] newUid(int typeId) {
        if (typeId < 0 || typeId > 0xFFFF) {
            throw new IllegalArgumentException("typeId out of u16 range");
        }
        ByteBuffer buf = ByteBuffer.allocate(UID_LEN).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(UID_MAGIC);
        buf.putShort((short) typeId);
        buf.putLong(RND.nextLong());
        return buf.array();
    }

    public static int typeIdFromUid(byte[] uid) {
        if (uid == null || uid.length < UID_LEN) {
            throw new WirepackException("ENTITY_FRAG uid too short");
        }
        ByteBuffer buf = ByteBuffer.wrap(uid).order(ByteOrder.BIG_ENDIAN);
        int magic = buf.getInt();
        if (magic != UID_MAGIC) {
            throw new WirepackException("ENTITY_FRAG uid magic mismatch");
        }
        return Short.toUnsignedInt(buf.getShort());
    }

    /**
     * Stateful reassembler. {@link #accept(Entity)} returns a completed logical entity when a
     * frag set finishes; empty for incomplete frags. Non-frag entities return empty (caller
     * handles wholes separately).
     */
    public static final class Reassembler {
        private final Map<Long, Partial> partials = new HashMap<>();
        private final int maxPartials;

        public Reassembler() {
            this(256);
        }

        public Reassembler(int maxPartials) {
            this.maxPartials = Math.max(1, maxPartials);
        }

        public Optional<Entity> accept(Entity e) {
            if (e.typeId() != BuiltinTypeId.ENTITY_FRAG.id()) {
                return Optional.empty();
            }
            RegisterCodec.EntityFrag frag = RegisterCodec.decodeEntityFrag(e.valueRaw());
            int typeId;
            try {
                typeId = typeIdFromUid(frag.uid());
            } catch (WirepackException ex) {
                // Foreign / bench UIDs — ignore in session tree path
                return Optional.empty();
            }
            long key = uidKey(frag.uid());
            Partial p = partials.get(key);
            if (p == null) {
                if (partials.size() >= maxPartials) {
                    throw new WirepackException("too many incomplete ENTITY_FRAG sets");
                }
                p = new Partial(typeId, frag.fragCount());
                partials.put(key, p);
            }
            if (p.fragCount != frag.fragCount() || p.typeId != typeId) {
                throw new WirepackException("ENTITY_FRAG metadata mismatch");
            }
            if (frag.fragIndex() < 0 || frag.fragIndex() >= p.fragCount) {
                throw new WirepackException("fragIndex out of range");
            }
            if (p.parts[frag.fragIndex()] != null) {
                return Optional.empty();
            }
            p.parts[frag.fragIndex()] = frag.data();
            p.got++;
            if (p.got != p.fragCount) {
                return Optional.empty();
            }
            partials.remove(key);
            int total = 0;
            for (byte[] part : p.parts) {
                total = Math.addExact(total, part.length);
            }
            byte[] value = new byte[total];
            int off = 0;
            for (byte[] part : p.parts) {
                System.arraycopy(part, 0, value, off, part.length);
                off += part.length;
            }
            return Optional.of(new Entity(p.typeId, value));
        }

        /** Feed entity; if a logical entity completes, invoke {@code onComplete}. */
        public void accept(Entity e, Consumer<Entity> onComplete) {
            accept(e).ifPresent(onComplete);
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
            final int typeId;
            final int fragCount;
            final byte[][] parts;
            int got;

            Partial(int typeId, int fragCount) {
                this.typeId = typeId;
                this.fragCount = fragCount;
                this.parts = new byte[fragCount][];
            }
        }
    }
}
