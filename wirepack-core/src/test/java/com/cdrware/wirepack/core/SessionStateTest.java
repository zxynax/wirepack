package com.cdrware.wirepack.core;

import com.cdrware.wirepack.api.SafetyLimits;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionStateTest {
    @Test
    void contiguousReceiveAdvancesLastRcv() {
        SessionState s = new SessionState(2048);
        assertTrue(s.onData(0));
        assertEquals(0, s.lastRcv());
        assertTrue(s.onData(1));
        assertEquals(1, s.lastRcv());
        assertFalse(s.onData(1)); // dup
    }

    @Test
    void gapThenFill() {
        SessionState s = new SessionState(2048);
        assertTrue(s.onData(0));
        assertTrue(s.onData(2)); // gap at 1
        assertEquals(0, s.lastRcv());
        assertTrue(s.onData(1));
        assertEquals(2, s.lastRcv());
    }

    @Test
    void retransmitBufferCaps() {
        RetransmitBuffer buf = new RetransmitBuffer(2, 1000);
        buf.put(1, new byte[10]);
        buf.put(2, new byte[10]);
        assertEquals(2, buf.size());
    }

    @Test
    void wrapWindowMath() {
        assertEquals(1, SeqMath.forwardDistance(0xFFFF, 0));
        assertTrue(SeqMath.inReceiveWindow(0xFFFF, 0, SafetyLimits.DEFAULT_WINDOW_SIZE));
    }

    @Test
    void sendWindowBlocksPastPeerCredit() {
        SessionState s = new SessionState(4);
        assertTrue(s.canSend());
        assertEquals(0, s.allocateSendSeq());
        assertEquals(1, s.allocateSendSeq());
        assertEquals(2, s.allocateSendSeq());
        assertEquals(3, s.allocateSendSeq());
        assertFalse(s.canSend()); // next would be 4, dist from 0xFFFF is 5 > window 4
        s.onPeerLastRcv(1);
        assertTrue(s.canSend());
        assertEquals(4, s.allocateSendSeq());
        assertEquals(5, s.allocateSendSeq());
        assertFalse(s.canSend());
    }
}
