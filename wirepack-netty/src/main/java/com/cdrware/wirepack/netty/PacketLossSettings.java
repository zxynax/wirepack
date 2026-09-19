package com.cdrware.wirepack.netty;

import com.cdrware.wirepack.api.MsgType;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optional UDP datagram loss for soak / reliability benches.
 * When {@code dataOnly} is true (default), HELLO / ACK / REQ / BYE are never dropped
 * so handshakes and reliability control can still function under load.
 */
public final class PacketLossSettings {
    private final double probability;
    private final long seed;
    private final boolean dataOnly;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong considered = new AtomicLong();
    private final ThreadLocal<java.util.Random> random;

    public PacketLossSettings(double probability, long seed, boolean dataOnly) {
        if (probability < 0.0 || probability > 1.0) {
            throw new IllegalArgumentException("probability must be in [0,1]");
        }
        this.probability = probability;
        this.seed = seed;
        this.dataOnly = dataOnly;
        this.random = ThreadLocal.withInitial(() -> new java.util.Random(seed ^ Thread.currentThread().threadId()));
    }

    public static PacketLossSettings none() {
        return new PacketLossSettings(0.0, 0L, true);
    }

    public static PacketLossSettings of(double probability, long seed) {
        return new PacketLossSettings(probability, seed, true);
    }

    public double probability() {
        return probability;
    }

    public long seed() {
        return seed;
    }

    public boolean dataOnly() {
        return dataOnly;
    }

    public long dropped() {
        return dropped.get();
    }

    public long considered() {
        return considered.get();
    }

    public boolean enabled() {
        return probability > 0.0;
    }

    /**
     * @return true if the datagram should be discarded
     */
    public boolean shouldDrop(byte[] frame) {
        if (probability <= 0.0 || frame == null || frame.length < 8) {
            return false;
        }
        if (dataOnly && !isDataFrame(frame)) {
            return false;
        }
        considered.incrementAndGet();
        if (random.get().nextDouble() < probability) {
            dropped.incrementAndGet();
            return true;
        }
        return false;
    }

    /** msgType is at offset 7: magic(2)+version(1)+keyId(4). */
    static boolean isDataFrame(byte[] frame) {
        try {
            int code = Byte.toUnsignedInt(frame[7]);
            return MsgType.fromCode(code) == MsgType.DATA;
        } catch (RuntimeException e) {
            return true; // unknown → eligible for loss
        }
    }
}
