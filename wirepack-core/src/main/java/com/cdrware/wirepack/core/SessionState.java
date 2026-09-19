package com.cdrware.wirepack.core;

import com.cdrware.wirepack.api.SafetyLimits;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Receiver / sender reliability state for one stream.
 */
public final class SessionState {
    private final int windowSize;
    private int lastRcv;          // cumulative watermark: highest contiguous seq received
    private int nextSendSeq;      // next DATA seq to assign
    private int peerLastRcv;      // peer's cumulative ACK watermark (send-side credit)
    private final BitSet received; // relative to lastRcv+1 .. lastRcv+window
    private boolean initialized;

    public SessionState(int windowSize) {
        this.windowSize = windowSize > 0 ? windowSize : SafetyLimits.DEFAULT_WINDOW_SIZE;
        this.received = new BitSet(this.windowSize + 1);
        this.lastRcv = 0xFFFF; // so first seq 0 is expected (distance 1)
        this.peerLastRcv = 0xFFFF; // nothing ACKed yet
        this.nextSendSeq = 0;
        this.initialized = false;
    }

    public SessionState() {
        this(SafetyLimits.DEFAULT_WINDOW_SIZE);
    }

    public int windowSize() {
        return windowSize;
    }

    public int lastRcv() {
        return lastRcv & 0xFFFF;
    }

    public synchronized int nextSendSeq() {
        return nextSendSeq & 0xFFFF;
    }

    /**
     * True if the next outbound DATA seq is still within the peer's receive window
     * ({@code peerLastRcv < nextSendSeq <= peerLastRcv + windowSize}).
     */
    public synchronized boolean canSend() {
        int candidate = nextSendSeq & 0xFFFF;
        int dist = SeqMath.forwardDistance(peerLastRcv, candidate);
        return dist >= 1 && dist <= windowSize;
    }

    public synchronized int peerLastRcv() {
        return peerLastRcv & 0xFFFF;
    }

    /** How many DATA seqs have been assigned but not yet covered by peerLastRcv. */
    public synchronized int inFlight() {
        if (nextSendSeq == 0 && peerLastRcv == 0xFFFF) {
            return 0;
        }
        int lastSent = (nextSendSeq - 1) & 0xFFFF;
        return SeqMath.forwardDistance(peerLastRcv, lastSent);
    }

    public synchronized int allocateSendSeq() {
        if (!canSendUnlocked()) {
            throw new IllegalStateException("send window full");
        }
        int s = nextSendSeq & 0xFFFF;
        nextSendSeq = SeqMath.next(nextSendSeq);
        return s;
    }

    private boolean canSendUnlocked() {
        int candidate = nextSendSeq & 0xFFFF;
        int dist = SeqMath.forwardDistance(peerLastRcv, candidate);
        return dist >= 1 && dist <= windowSize;
    }

    /**
     * Process inbound DATA seq. Returns true if this is a new in-order-or-window packet
     * that should be delivered (duplicates return false).
     */
    public synchronized boolean onData(int seq) {
        seq &= 0xFFFF;
        if (!initialized) {
            // Accept first packet as establishing baseline: if seq==0 and lastRcv==0xFFFF OK
            initialized = true;
        }

        int dist = SeqMath.forwardDistance(lastRcv, seq);
        if (dist == 0) {
            return false; // duplicate of lastRcv (shouldn't happen for DATA)
        }
        if (dist > windowSize) {
            return false; // outside window
        }

        if (dist == 1) {
            // Contiguous advance
            lastRcv = seq;
            // Shift bitset: consume consecutive bits
            received.clear(0); // unused index
            // After advancing, slide: bits that were at offset 2.. become 1..
            BitSet next = new BitSet(windowSize + 1);
            for (int i = 2; i <= windowSize; i++) {
                if (received.get(i)) {
                    next.set(i - 1);
                }
            }
            received.clear();
            received.or(next);
            // Drain any contiguous buffered packets
            while (received.get(1)) {
                lastRcv = SeqMath.next(lastRcv);
                BitSet slide = new BitSet(windowSize + 1);
                for (int i = 2; i <= windowSize; i++) {
                    if (received.get(i)) {
                        slide.set(i - 1);
                    }
                }
                received.clear();
                received.or(slide);
            }
            return true;
        }

        // Gap: mark as received at offset dist
        if (received.get(dist)) {
            return false; // duplicate
        }
        received.set(dist);
        return true;
    }

    /**
     * Apply peer's lastRcv / selective ack to advance send side (caller also clears retransmit buffer).
     */
    public synchronized void onPeerLastRcv(int peerLastRcv) {
        this.peerLastRcv = peerLastRcv & 0xFFFF;
    }

    public synchronized List<int[]> gaps() {
        List<int[]> ranges = new ArrayList<>();
        int start = -1;
        for (int dist = 1; dist <= windowSize; dist++) {
            boolean missing = !received.get(dist);
            // Only report gaps that are before some received packet or we could probe —
            // for v0: any hole between lastRcv+1 and highest received
            if (missing) {
                if (start < 0) {
                    start = dist;
                }
            } else if (start >= 0) {
                ranges.add(new int[]{
                        SeqMath.add(lastRcv, start),
                        SeqMath.add(lastRcv, dist - 1)
                });
                start = -1;
            }
        }
        // Only include trailing gap if something was received beyond it
        int highest = highestReceivedOffset();
        if (start >= 0 && highest >= start) {
            ranges.add(new int[]{
                    SeqMath.add(lastRcv, start),
                    SeqMath.add(lastRcv, highest)
            });
        } else if (start >= 0) {
            // no higher received — don't REQ speculative future
        }
        return ranges;
    }

    private int highestReceivedOffset() {
        int h = 0;
        for (int i = 1; i <= windowSize; i++) {
            if (received.get(i)) {
                h = i;
            }
        }
        return h;
    }

    /**
     * ACK payload: optional ranges as [start:u16][end:u16]* (inclusive). Empty payload = cumulative only.
     */
    public byte[] buildAckPayload() {
        // v0: empty payload — cumulative lastRcv in header is enough
        return new byte[0];
    }

    /**
     * REQ payload: missing seq ranges [start:u16][end:u16]* inclusive.
     */
    public synchronized byte[] buildReqPayload() {
        List<int[]> g = gaps();
        if (g.isEmpty()) {
            return new byte[0];
        }
        ByteBuffer buf = ByteBuffer.allocate(g.size() * 4).order(ByteOrder.BIG_ENDIAN);
        for (int[] r : g) {
            buf.putShort((short) r[0]);
            buf.putShort((short) r[1]);
        }
        return buf.array();
    }

    public static List<int[]> parseRanges(byte[] payload) {
        List<int[]> out = new ArrayList<>();
        if (payload == null || payload.length == 0) {
            return out;
        }
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        while (buf.remaining() >= 4) {
            int start = Short.toUnsignedInt(buf.getShort());
            int end = Short.toUnsignedInt(buf.getShort());
            out.add(new int[]{start, end});
        }
        return out;
    }
}
