package com.cdrware.wirepack.core;

/**
 * u16 sequence wrap helpers with sliding window of size {@code windowSize} (default 2048).
 */
public final class SeqMath {
    private SeqMath() {}

    /** Distance of {@code seq} ahead of {@code base} in u16 space (0..65535). */
    public static int forwardDistance(int base, int seq) {
        return (seq - base) & 0xFFFF;
    }

    /**
     * True if {@code seq} is within the receive window after {@code lastRcv}
     * (i.e. lastRcv &lt; seq &lt;= lastRcv + window), with wrap.
     */
    public static boolean inReceiveWindow(int lastRcv, int seq, int windowSize) {
        int dist = forwardDistance(lastRcv, seq);
        return dist >= 1 && dist <= windowSize;
    }

    /**
     * True if {@code seq} has been cumulatively acknowledged by {@code lastRcv}
     * within the send window (seq is behind or equal to lastRcv and within window).
     */
    public static boolean isAcknowledged(int seq, int lastRcv, int windowSize) {
        int dist = forwardDistance(seq, lastRcv);
        // lastRcv is at or ahead of seq by at most windowSize (inclusive of equality → dist 0)
        return dist <= windowSize;
    }

    public static int next(int seq) {
        return (seq + 1) & 0xFFFF;
    }

    public static int add(int seq, int delta) {
        return (seq + delta) & 0xFFFF;
    }
}
