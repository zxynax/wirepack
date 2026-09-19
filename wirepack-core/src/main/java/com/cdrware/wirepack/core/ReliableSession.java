package com.cdrware.wirepack.core;

import com.cdrware.wirepack.api.BuiltinTypeId;
import com.cdrware.wirepack.api.Entity;
import com.cdrware.wirepack.api.FrameHeader;
import com.cdrware.wirepack.api.MsgType;
import com.cdrware.wirepack.api.PeerAddress;
import com.cdrware.wirepack.api.SafetyLimits;
import com.cdrware.wirepack.api.Session;
import com.cdrware.wirepack.api.WirepackConfig;
import com.cdrware.wirepack.api.WirepackException;
import com.cdrware.wirepack.codec.EntityReader;
import com.cdrware.wirepack.codec.EntityWriter;
import com.cdrware.wirepack.codec.FrameCodec;
import com.cdrware.wirepack.crypto.ChaCha20Poly1305;
import com.cdrware.wirepack.crypto.SessionCrypto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Reliable session: HELLO/crypto (optional), DATA, ACK/REQ, retransmit.
 *
 * <p>Send window: new DATA seqs are not allocated while in-flight would exceed the peer
 * receive window. Keepalive: when unacked DATA sits idle (no ACK progress), the oldest
 * buffered frames are resent — covers lost trailing DATA / lost ACK when reactive REQ
 * cannot run.
 */
public final class ReliableSession implements Session {
    private static final Logger log = LoggerFactory.getLogger(ReliableSession.class);
    /** nonce(12) + tag(16) overhead for sealed payloads. */
    private static final int AEAD_OVERHEAD = ChaCha20Poly1305.NONCE_LEN + ChaCha20Poly1305.TAG_LEN;
    /** Idle time with unacked DATA before proactive retransmit (keepalive). */
    private static final long RETRANSMIT_TIMEOUT_MS = 100L;
    /** Max frames resent per keepalive tick. */
    private static final int KEEPALIVE_BATCH = 32;
    /** Max wait for send-window credit before failing the send. */
    private static final long SEND_CREDIT_WAIT_MS = 30_000L;

    private final int sessionId;
    private final PeerAddress peer;
    private final WirepackConfig config;
    private final FrameCodec frameCodec;
    private final SessionState state;
    private final RetransmitBuffer retransmit;
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final CopyOnWriteArrayList<Consumer<Entity>> entityHandlers = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Runnable> closeHandlers = new CopyOnWriteArrayList<>();
    private final int streamId;
    private final SessionCrypto crypto;
    private final CompletableFuture<Void> handshake = new CompletableFuture<>();
    private final AtomicBoolean helloSent = new AtomicBoolean(false);
    private final Object sendCredit = new Object();

    private volatile BiConsumer<PeerAddress, byte[]> sender;
    private volatile long lastAckProgressMs = System.currentTimeMillis();
    private volatile long lastProactiveRetransmitMs;

    public ReliableSession(int sessionId, PeerAddress peer, WirepackConfig config) {
        this(sessionId, peer, config, 0);
    }

    public ReliableSession(int sessionId, PeerAddress peer, WirepackConfig config, int streamId) {
        this.sessionId = sessionId;
        this.peer = Objects.requireNonNull(peer, "peer");
        this.config = Objects.requireNonNull(config, "config");
        this.streamId = streamId;
        this.frameCodec = new FrameCodec(config.limits());
        this.state = new SessionState(config.windowSize());
        this.retransmit = new RetransmitBuffer(config.limits());
        byte[] psk = config.sharedKeyRaw();
        if (psk != null && psk.length > 0) {
            this.crypto = SessionCrypto.create(psk, config.keyId(), config.limits().rekeyAfterPackets());
        } else {
            this.crypto = null;
            handshake.complete(null);
        }
    }

    public void setSender(BiConsumer<PeerAddress, byte[]> sender) {
        this.sender = sender;
    }

    public boolean cryptoEnabled() {
        return crypto != null;
    }

    public CompletableFuture<Void> handshakeFuture() {
        return handshake;
    }

    @Override
    public int sessionId() {
        return sessionId;
    }

    @Override
    public PeerAddress peer() {
        return peer;
    }

    public SessionState state() {
        return state;
    }

    public RetransmitBuffer retransmitBuffer() {
        return retransmit;
    }

    @Override
    public CompletableFuture<Void> send(List<Entity> entities) {
        if (!open.get()) {
            return CompletableFuture.failedFuture(new WirepackException("session closed"));
        }
        if (crypto != null && !crypto.handshakeComplete()) {
            return handshake.thenCompose(v -> send(entities));
        }
        try {
            doSend(entities);
            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private void doSend(List<Entity> entities) {
        SafetyLimits limits = config.limits();
        List<Entity> remaining = new ArrayList<>(entities);
        if (crypto != null && crypto.shouldRekey()) {
            remaining.add(0, crypto.buildRekeyOffer());
        }
        while (!remaining.isEmpty()) {
            awaitSendCredit();
            int maxPlain = limits.maxPayloadBytes();
            if (crypto != null) {
                maxPlain = Math.max(0, maxPlain - AEAD_OVERHEAD);
            }
            EntityWriter writer = new EntityWriter(limits.toBuilder().maxPayloadBytes(maxPlain).build());
            while (!remaining.isEmpty()) {
                Entity next = remaining.get(0);
                if (!writer.tryAdd(next)) {
                    if (writer.count() == 0) {
                        throw new WirepackException("single entity exceeds maxPayloadBytes");
                    }
                    break;
                }
                remaining.remove(0);
            }
            writer.finishWithEoe();
            byte[] plain = writer.toByteArray();
            int seq = state.allocateSendSeq();
            int keyId = crypto != null ? crypto.currentKeyId() : config.keyId();
            byte[] wirePayload = plain;
            if (crypto != null) {
                int sealedLen = plain.length + AEAD_OVERHEAD;
                FrameHeader provisional = new FrameHeader(
                        FrameHeader.VERSION_V0, keyId, MsgType.DATA, sessionId, streamId,
                        seq, state.lastRcv(), sealedLen);
                wirePayload = crypto.sealTraffic(FrameCodec.headerBytes(provisional), plain);
            }
            FrameHeader header = new FrameHeader(
                    FrameHeader.VERSION_V0, keyId, MsgType.DATA, sessionId, streamId,
                    seq, state.lastRcv(), wirePayload.length);
            byte[] frame = frameCodec.encode(header, wirePayload);
            retransmit.put(seq, frame);
            if (retransmit.size() == 1) {
                lastAckProgressMs = System.currentTimeMillis();
            }
            sendRaw(frame);
        }
    }

    private void awaitSendCredit() {
        if (state.canSend()) {
            return;
        }
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(SEND_CREDIT_WAIT_MS);
        synchronized (sendCredit) {
            while (!state.canSend()) {
                if (!open.get()) {
                    throw new WirepackException("session closed while waiting for send window");
                }
                long remainingNs = deadline - System.nanoTime();
                if (remainingNs <= 0) {
                    throw new WirepackException("send window full (waiting for ACK)");
                }
                // While blocked on credit, proactive retransmit may be the only path forward.
                proactiveRetransmitIfDue();
                long waitMs = Math.min(25L, Math.max(1L, remainingNs / 1_000_000L));
                try {
                    sendCredit.wait(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new WirepackException("interrupted waiting for send window");
                }
            }
        }
    }

    public void sendRaw(byte[] frame) {
        BiConsumer<PeerAddress, byte[]> s = sender;
        if (s == null) {
            throw new WirepackException("no sender bound to session");
        }
        s.accept(peer, frame);
    }

    public void onFrame(FrameCodec.DecodedFrame frame) {
        if (!open.get()) {
            return;
        }
        FrameHeader h = frame.header();
        int beforePeer = state.peerLastRcv();
        retransmit.removeUpToInclusive(h.lastRcv(), config.windowSize());
        state.onPeerLastRcv(h.lastRcv());
        if (state.peerLastRcv() != beforePeer) {
            lastAckProgressMs = System.currentTimeMillis();
            synchronized (sendCredit) {
                sendCredit.notifyAll();
            }
        }

        switch (h.msgType()) {
            case DATA -> handleData(h, frame.payloadRaw());
            case ACK -> { /* cumulative already applied */ }
            case REQ -> handleReq(openIfNeeded(h, frame.payloadRaw()));
            case HELLO -> handleHello(h, frame.payloadRaw());
            case BYE -> close();
        }
    }

    /**
     * Periodic reliability tick (transport event loop). When unacked DATA has gone idle
     * with no ACK progress, resend oldest buffered frames so the peer can ACK/REQ again.
     */
    public void onReliabilityTick() {
        if (!open.get()) {
            return;
        }
        proactiveRetransmitIfDue();
        // Re-send REQ for known gaps when idle (covers lost REQ). No speculative trailing REQ.
        byte[] req = state.buildReqPayload();
        if (req.length > 0 && idlePastRetransmitTimeout()) {
            try {
                sendControl(MsgType.REQ, req);
                lastProactiveRetransmitMs = System.currentTimeMillis();
            } catch (Exception e) {
                log.trace("keepalive REQ failed: {}", e.toString());
            }
        }
    }

    private boolean idlePastRetransmitTimeout() {
        long now = System.currentTimeMillis();
        return now - lastAckProgressMs >= RETRANSMIT_TIMEOUT_MS
                && now - lastProactiveRetransmitMs >= RETRANSMIT_TIMEOUT_MS;
    }

    private void proactiveRetransmitIfDue() {
        if (retransmit.isEmpty()) {
            return;
        }
        if (!idlePastRetransmitTimeout()) {
            return;
        }
        List<byte[]> frames = retransmit.oldestFrames(KEEPALIVE_BATCH);
        if (frames.isEmpty()) {
            return;
        }
        lastProactiveRetransmitMs = System.currentTimeMillis();
        for (byte[] frame : frames) {
            try {
                sendRaw(frame);
            } catch (Exception e) {
                log.trace("keepalive retransmit failed: {}", e.toString());
                break;
            }
        }
    }

    private void handleHello(FrameHeader h, byte[] payload) {
        if (crypto == null) {
            log.debug("HELLO (cleartext) from {}", peer);
            return;
        }
        try {
            crypto.acceptPeerHello(FrameCodec.headerBytes(h), payload);
            // Responder: advertise our pub once if we have not sent HELLO yet.
            if (helloSent.compareAndSet(false, true)) {
                sendHelloInternal();
            }
            if (crypto.handshakeComplete()) {
                handshake.complete(null);
            }
        } catch (Exception e) {
            log.warn("HELLO crypto failed from {}: {}", peer, e.getMessage());
            handshake.completeExceptionally(e);
        }
    }

    private void handleData(FrameHeader h, byte[] wirePayload) {
        boolean deliver = state.onData(h.seq());
        if (deliver) {
            byte[] plain;
            try {
                plain = openIfNeeded(h, wirePayload);
            } catch (Exception e) {
                log.debug("drop DATA open failure: {}", e.getMessage());
                return;
            }
            EntityReader reader = new EntityReader(plain, config.limits());
            List<Entity> app = new ArrayList<>();
            for (Entity e : reader.readAll()) {
                if (e.typeId() == BuiltinTypeId.REKEY_OFFER.id() && crypto != null) {
                    try {
                        Entity accept = crypto.buildRekeyAccept(e.valueRaw());
                        send(List.of(accept));
                    } catch (Exception ex) {
                        log.warn("rekey offer handling failed", ex);
                    }
                } else if (e.typeId() == BuiltinTypeId.REKEY_ACCEPT.id() && crypto != null) {
                    try {
                        crypto.onRekeyAccept(e.valueRaw());
                    } catch (Exception ex) {
                        log.warn("rekey accept handling failed", ex);
                    }
                } else if (e.typeId() != BuiltinTypeId.EOE.id()) {
                    app.add(e);
                }
            }
            for (Entity e : app) {
                for (Consumer<Entity> c : entityHandlers) {
                    try {
                        c.accept(e);
                    } catch (Exception ex) {
                        log.warn("entity handler error", ex);
                    }
                }
            }
        }
        sendControl(MsgType.ACK, state.buildAckPayload());
        byte[] req = state.buildReqPayload();
        if (req.length > 0) {
            sendControl(MsgType.REQ, req);
        }
    }

    private byte[] openIfNeeded(FrameHeader h, byte[] wirePayload) {
        if (crypto == null || wirePayload.length == 0) {
            return wirePayload;
        }
        if (!crypto.handshakeComplete() && h.msgType() != MsgType.HELLO) {
            throw new WirepackException("encrypted frame before handshake complete");
        }
        if (h.msgType() == MsgType.HELLO) {
            return wirePayload;
        }
        return crypto.openTraffic(h.keyId(), FrameCodec.headerBytes(h), wirePayload);
    }

    private void handleReq(byte[] payload) {
        for (int[] range : SessionState.parseRanges(payload)) {
            int start = range[0];
            int end = range[1];
            int seq = start;
            while (true) {
                retransmit.get(seq).ifPresent(this::sendRaw);
                if (seq == end) {
                    break;
                }
                seq = SeqMath.next(seq);
            }
        }
    }

    private void sendControl(MsgType type, byte[] payload) {
        int keyId = crypto != null && crypto.handshakeComplete()
                ? crypto.currentKeyId() : config.keyId();
        byte[] wire = payload;
        if (crypto != null && crypto.handshakeComplete() && payload.length > 0) {
            int sealedLen = payload.length + AEAD_OVERHEAD;
            FrameHeader provisional = new FrameHeader(
                    FrameHeader.VERSION_V0, keyId, type, sessionId, streamId,
                    0, state.lastRcv(), sealedLen);
            wire = crypto.sealTraffic(FrameCodec.headerBytes(provisional), payload);
        }
        FrameHeader header = new FrameHeader(
                FrameHeader.VERSION_V0, keyId, type, sessionId, streamId,
                0, state.lastRcv(), wire.length);
        sendRaw(frameCodec.encode(header, wire));
    }

    public void sendHello() {
        if (helloSent.compareAndSet(false, true)) {
            sendHelloInternal();
        }
    }

    private void sendHelloInternal() {
        if (crypto == null) {
            sendControl(MsgType.HELLO, new byte[0]);
            return;
        }
        int keyId = config.keyId();
        int sealedLen = X25519_PUB_SEALED_LEN;
        FrameHeader provisional = new FrameHeader(
                FrameHeader.VERSION_V0, keyId, MsgType.HELLO, sessionId, streamId,
                0, state.lastRcv(), sealedLen);
        byte[] sealed = crypto.sealHello(FrameCodec.headerBytes(provisional));
        if (sealed.length != sealedLen) {
            // recompute if AEAD size differs
            provisional = new FrameHeader(
                    FrameHeader.VERSION_V0, keyId, MsgType.HELLO, sessionId, streamId,
                    0, state.lastRcv(), sealed.length);
            sealed = crypto.sealHello(FrameCodec.headerBytes(provisional));
        }
        FrameHeader header = new FrameHeader(
                FrameHeader.VERSION_V0, keyId, MsgType.HELLO, sessionId, streamId,
                0, state.lastRcv(), sealed.length);
        sendRaw(frameCodec.encode(header, sealed));
    }

    /** 32-byte pub + 12 nonce + 16 tag. */
    private static final int X25519_PUB_SEALED_LEN = 32 + AEAD_OVERHEAD;

    @Override
    public void onEntity(Consumer<Entity> handler) {
        entityHandlers.add(Objects.requireNonNull(handler));
    }

    @Override
    public void onClose(Runnable handler) {
        closeHandlers.add(Objects.requireNonNull(handler));
    }

    @Override
    public boolean isOpen() {
        return open.get();
    }

    @Override
    public void close() {
        if (!open.compareAndSet(true, false)) {
            return;
        }
        synchronized (sendCredit) {
            sendCredit.notifyAll();
        }
        try {
            sendControl(MsgType.BYE, new byte[0]);
        } catch (Exception e) {
            log.debug("BYE send failed", e);
        }
        for (Runnable r : closeHandlers) {
            try {
                r.run();
            } catch (Exception e) {
                log.warn("close handler error", e);
            }
        }
    }
}
