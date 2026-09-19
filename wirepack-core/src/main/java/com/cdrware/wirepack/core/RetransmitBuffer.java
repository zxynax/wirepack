package com.cdrware.wirepack.core;

import com.cdrware.wirepack.api.SafetyLimits;
import com.cdrware.wirepack.api.WirepackException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bounded retransmit buffer: seq → frame bytes.
 */
public final class RetransmitBuffer {
    private final int maxEntries;
    private final int maxBytes;
    private final LinkedHashMap<Integer, byte[]> entries = new LinkedHashMap<>();
    private int totalBytes;

    public RetransmitBuffer(SafetyLimits limits) {
        this.maxEntries = limits.maxRetransmitEntries();
        this.maxBytes = limits.maxRetransmitBytes();
    }

    public RetransmitBuffer(int maxEntries, int maxBytes) {
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
    }

    public synchronized void put(int seq, byte[] frame) {
        int key = seq & 0xFFFF;
        byte[] copy = frame.clone();
        byte[] prev = entries.get(key);
        int nextBytes = totalBytes - (prev == null ? 0 : prev.length) + copy.length;
        if (prev == null && entries.size() >= maxEntries) {
            throw new WirepackException("retransmit buffer entry limit reached");
        }
        if (nextBytes > maxBytes) {
            throw new WirepackException("retransmit buffer byte limit reached");
        }
        entries.put(key, copy);
        totalBytes = nextBytes;
    }

    public synchronized Optional<byte[]> get(int seq) {
        byte[] b = entries.get(seq & 0xFFFF);
        return b == null ? Optional.empty() : Optional.of(b.clone());
    }

    public synchronized void removeUpToInclusive(int lastRcv, int windowSize) {
        // Remove entries that are acknowledged: seq in (lastRcv - window, lastRcv] with wrap
        entries.entrySet().removeIf(e -> {
            if (SeqMath.isAcknowledged(e.getKey(), lastRcv, windowSize)) {
                totalBytes -= e.getValue().length;
                return true;
            }
            return false;
        });
    }

    public synchronized void remove(int seq) {
        byte[] removed = entries.remove(seq & 0xFFFF);
        if (removed != null) {
            totalBytes -= removed.length;
        }
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized int bytes() {
        return totalBytes;
    }

    public synchronized List<byte[]> oldestFrames(int max) {
        List<byte[]> out = new ArrayList<>(Math.max(0, max));
        if (max <= 0) {
            return out;
        }
        for (byte[] frame : entries.values()) {
            out.add(frame.clone());
            if (out.size() >= max) {
                break;
            }
        }
        return out;
    }

    public synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    public synchronized Map<Integer, byte[]> snapshot() {
        LinkedHashMap<Integer, byte[]> copy = new LinkedHashMap<>();
        for (Map.Entry<Integer, byte[]> e : entries.entrySet()) {
            copy.put(e.getKey(), e.getValue().clone());
        }
        return copy;
    }
}
