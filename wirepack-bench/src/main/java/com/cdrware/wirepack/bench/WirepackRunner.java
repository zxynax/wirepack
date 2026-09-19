package com.cdrware.wirepack.bench;

import com.cdrware.wirepack.api.BindAddress;
import com.cdrware.wirepack.api.Entity;
import com.cdrware.wirepack.api.PeerAddress;
import com.cdrware.wirepack.api.SafetyLimits;
import com.cdrware.wirepack.api.Session;
import com.cdrware.wirepack.api.SessionAcceptor;
import com.cdrware.wirepack.api.Wirepack;
import com.cdrware.wirepack.api.WirepackConfig;
import com.cdrware.wirepack.api.WirepackException;
import com.cdrware.wirepack.api.WirepackRuntime;
import com.cdrware.wirepack.netty.NettyUdpTransportFactory;
import com.cdrware.wirepack.netty.PacketLossSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class WirepackRunner {
    /** ChaCha20-Poly1305 nonce(12) + tag(16); must match ReliableSession. */
    private static final int AEAD_OVERHEAD = 28;

    private WirepackRunner() {
    }

    static BenchResult run(Scenario scenario, BenchArgs args) throws Exception {
        int sessions = args.sessionsFor(scenario);
        int count = args.countFor(scenario);
        int size = args.sizeFor(scenario);
        if (sessions <= 1) {
            return runSingle(count, size, args);
        }
        return runMulti(count, size, sessions, args);
    }

    private static int aeadOverhead(BenchArgs args) {
        return args.crypto() ? AEAD_OVERHEAD : 0;
    }

    private static BenchResult runSingle(int count, int size, BenchArgs args) throws Exception {
        SafetyLimits limits = limitsFor(args);
        PacketLossSettings loss = lossFor(args, 0);
        WirepackConfig listenCfg = config(args, limits, PacketLossSettings.none());
        WirepackConfig connectCfg = config(args, limits, loss);

        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger();

        try (WirepackRuntime listenRt = Wirepack.open(listenCfg);
             WirepackRuntime connectRt = Wirepack.open(connectCfg)) {

            SessionAcceptor acceptor = listenRt.listen(new BindAddress("127.0.0.1", 0))
                    .get(10, TimeUnit.SECONDS);
            int port = acceptor.bindAddress().port();

            acceptor.onAccept(session -> {
                FragSupport.Reassembler reassembler = new FragSupport.Reassembler(n -> {
                    completed.set(n);
                    if (n >= count) {
                        done.countDown();
                    }
                });
                session.onEntity(reassembler);
            });

            Session client = connectRt.connect(new PeerAddress("127.0.0.1", port))
                    .get(10, TimeUnit.SECONDS);

            List<byte[]> payloads = BenchPayloads.all(count, size);
            long payloadBytes = (long) count * size;

            long t0 = System.nanoTime();
            if (args.loss() > 0.0) {
                // Fire-and-forget paced send; session send-window + keepalive retransmit
                // recover gaps without stop-and-wait. pumpUntil covers idle tail loss.
                sendPaced(client, payloads, limits, args.batch(), args.timeoutMs(), aeadOverhead(args));
                pumpUntil(client, done, args.timeoutMs());
            } else {
                sendAll(client, payloads, limits, args.batch(), completed, count, args.timeoutMs(),
                        aeadOverhead(args));
            }
            if (!done.await(args.timeoutMs(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(
                        "wirepack timed out: got " + completed.get() + " / " + count
                                + lossHint(loss));
            }
            long elapsed = System.nanoTime() - t0;
            client.close();
            return new BenchResult(elapsed, count, payloadBytes);
        }
    }

    private static BenchResult runMulti(int perSession, int size, int sessions, BenchArgs args)
            throws Exception {
        SafetyLimits limits = limitsFor(args);
        int total = perSession * sessions;
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger();

        WirepackConfig listenCfg = config(args, limits, PacketLossSettings.none());
        List<WirepackRuntime> clientRuntimes = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(sessions);

        try (WirepackRuntime listenRt = Wirepack.open(listenCfg)) {
            SessionAcceptor acceptor = listenRt.listen(new BindAddress("127.0.0.1", 0))
                    .get(10, TimeUnit.SECONDS);
            int port = acceptor.bindAddress().port();

            acceptor.onAccept(session -> {
                FragSupport.Reassembler reassembler = new FragSupport.Reassembler(ignored -> {
                    if (completed.incrementAndGet() >= total) {
                        done.countDown();
                    }
                });
                session.onEntity(reassembler);
            });

            long t0 = System.nanoTime();
            List<Future<?>> futures = new ArrayList<>(sessions);
            for (int s = 0; s < sessions; s++) {
                final int sessionIndex = s;
                PacketLossSettings loss = lossFor(args, sessionIndex);
                WirepackConfig connectCfg = config(args, limits, loss);
                WirepackRuntime connectRt = Wirepack.open(connectCfg);
                clientRuntimes.add(connectRt);
                futures.add(pool.submit(() -> {
                    try {
                        Session client = connectRt.connect(new PeerAddress("127.0.0.1", port))
                                .get(15, TimeUnit.SECONDS);
                        List<byte[]> payloads = new ArrayList<>(perSession);
                        for (int i = 0; i < perSession; i++) {
                            payloads.add(BenchPayloads.payload(sessionIndex * perSession + i, size));
                        }
                        sendPaced(client, payloads, limits, args.batch(), args.timeoutMs(),
                                aeadOverhead(args));
                        pumpUntil(client, done, args.timeoutMs());
                        client.close();
                    } catch (Exception e) {
                        throw new RuntimeException("session " + sessionIndex + " failed", e);
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get(args.timeoutMs() + 60_000L, TimeUnit.MILLISECONDS);
            }
            if (!done.await(args.timeoutMs(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(
                        "wirepack multi timed out: got " + completed.get() + " / " + total);
            }
            long elapsed = System.nanoTime() - t0;
            return new BenchResult(elapsed, total, (long) total * size);
        } finally {
            pool.shutdownNow();
            for (WirepackRuntime rt : clientRuntimes) {
                try {
                    rt.close();
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
    }

    /** Send without waiting on local receive counters (multi-session uses shared latch). */
    private static void sendPaced(
            Session client,
            List<byte[]> payloads,
            SafetyLimits limits,
            int batch,
            long timeoutMs,
            int aeadOverhead
    ) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        int base = 0;
        if (!payloads.isEmpty() && payloads.get(0).length >= 4) {
            base = ((payloads.get(0)[0] & 0xff) << 24)
                    | ((payloads.get(0)[1] & 0xff) << 16)
                    | ((payloads.get(0)[2] & 0xff) << 8)
                    | (payloads.get(0)[3] & 0xff);
        }
        for (int i = 0; i < payloads.size(); i++) {
            List<Entity> wire = FragSupport.toWireEntities(base + i, payloads.get(i), limits, aeadOverhead);
            for (int off = 0; off < wire.size(); off += batch) {
                int end = Math.min(wire.size(), off + batch);
                flushBatch(client, wire.subList(off, end), deadline);
            }
            if ((i + 1) % Math.max(1, batch) == 0) {
                Thread.sleep(1);
            }
        }
    }

    /**
     * Send probe DATA frames so the peer can REQ missing seqs after tail loss.
     * Probe type is ignored by {@link FragSupport.Reassembler}.
     */
    private static void pumpUntil(Session client, CountDownLatch done, long timeoutMs)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        Entity probe = new Entity(2001, new byte[] {0x50}); // "P"
        int i = 0;
        while (done.getCount() > 0 && System.nanoTime() < deadline) {
            flushBatch(client, List.of(probe), deadline);
            Thread.sleep(Math.min(50, 5 + i));
            i++;
        }
    }

    private static SafetyLimits limitsFor(BenchArgs args) {
        return SafetyLimits.builder()
                .maxRetransmitEntries(16_384)
                .maxRetransmitBytes(32 * 1024 * 1024)
                .sessionIdleMs(Math.max(120_000L, args.timeoutMs() + 30_000L))
                .build();
    }

    private static PacketLossSettings lossFor(BenchArgs args, int salt) {
        if (args.loss() <= 0.0) {
            return PacketLossSettings.none();
        }
        return new PacketLossSettings(args.loss(), args.lossSeed() + salt * 31L, true);
    }

    private static WirepackConfig config(
            BenchArgs args, SafetyLimits limits, PacketLossSettings loss) {
        WirepackConfig.Builder b = WirepackConfig.builder()
                .limits(limits)
                .windowSize(SafetyLimits.DEFAULT_WINDOW_SIZE)
                .transport(new NettyUdpTransportFactory(loss));
        if (args.crypto() && args.sharedKey() != null) {
            b.sharedKey(args.sharedKey()).keyId(1);
        }
        return b.build();
    }

    private static String lossHint(PacketLossSettings loss) {
        if (loss == null || !loss.enabled()) {
            return "";
        }
        return " (loss dropped " + loss.dropped() + "/" + loss.considered() + " DATA frames)";
    }

    private static void sendAll(
            Session client,
            List<byte[]> payloads,
            SafetyLimits limits,
            int batch,
            AtomicInteger completed,
            int total,
            long timeoutMs,
            int aeadOverhead
    ) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        int smallSinceWait = 0;
        for (int i = 0; i < payloads.size(); i++) {
            List<Entity> wire = FragSupport.toWireEntities(i, payloads.get(i), limits, aeadOverhead);
            boolean multiFrag = wire.size() > 1
                    || (wire.size() == 1 && wire.get(0).typeId() != BenchPayloads.TYPE_ID);
            int before = completed.get();
            for (int off = 0; off < wire.size(); off += batch) {
                int end = Math.min(wire.size(), off + batch);
                flushBatch(client, wire.subList(off, end), deadline);
            }
            if (multiFrag) {
                waitUntil(completed, before + 1, deadline);
                smallSinceWait = 0;
            } else {
                smallSinceWait++;
                if (smallSinceWait >= batch) {
                    waitUntil(completed, Math.min(total, i + 1), deadline);
                    smallSinceWait = 0;
                }
            }
        }
        waitUntil(completed, total, deadline);
    }

    private static void waitUntil(AtomicInteger completed, int target, long deadlineNanos)
            throws InterruptedException {
        while (completed.get() < target) {
            if (System.nanoTime() > deadlineNanos) {
                return;
            }
            Thread.sleep(1);
        }
    }

    private static void flushBatch(Session client, List<Entity> chunk, long deadlineNanos)
            throws Exception {
        List<Entity> list = new ArrayList<>(chunk);
        int attempts = 0;
        while (true) {
            if (System.nanoTime() > deadlineNanos) {
                throw new IllegalStateException("wirepack send timed out (retransmit backpressure)");
            }
            try {
                client.send(list).get(30, TimeUnit.SECONDS);
                return;
            } catch (Exception e) {
                Throwable c = e.getCause() != null ? e.getCause() : e;
                if (c instanceof WirepackException
                        && c.getMessage() != null
                        && (c.getMessage().contains("retransmit buffer")
                        || c.getMessage().contains("send window full"))) {
                    if (++attempts > 2_000) {
                        throw e;
                    }
                    Thread.sleep(Math.min(50, 2L * attempts));
                    continue;
                }
                throw e;
            }
        }
    }
}
