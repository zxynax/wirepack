package com.cdrware.wirepack.bench;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loopback REST baseline. Small entities use JSON batches; large entities use
 * {@code application/octet-stream} one POST per entity. Multi-session uses
 * <strong>one HttpClient per simulated client system</strong> (no shared pool)
 * against a single receiver, matching wirepack's N independent connects.
 */
final class RestRunner {
    private RestRunner() {
    }

    static BenchResult run(Scenario scenario, BenchArgs args) throws Exception {
        int sessions = args.sessionsFor(scenario);
        int count = args.countFor(scenario);
        int size = args.sizeFor(scenario);
        int batch = args.batch();
        long timeoutMs = args.timeoutMs();

        if (sessions <= 1) {
            return runSingle(scenario, count, size, batch, timeoutMs);
        }
        return runMulti(scenario, count, size, batch, sessions, timeoutMs);
    }

    private static BenchResult runSingle(
            Scenario scenario, int count, int size, int batch, long timeoutMs) throws Exception {
        AtomicInteger completed = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);

        var executor = Executors.newFixedThreadPool(4);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/batch", ex -> handleBatch(ex, completed, count, done));
        server.createContext("/entity", ex -> handleEntity(ex, completed, count, done));
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        List<byte[]> payloads = BenchPayloads.all(count, size);
        long payloadBytes = (long) count * size;

        try {
            long t0 = System.nanoTime();
            if (scenario == Scenario.LARGE_FEW) {
                postRawEntities(client, base.resolve("/entity"), payloads);
            } else {
                postJsonBatches(client, base.resolve("/batch"), payloads, batch);
            }
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(
                        "rest timed out: got " + completed.get() + " / " + count);
            }
            long elapsed = System.nanoTime() - t0;
            return new BenchResult(elapsed, count, payloadBytes);
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static BenchResult runMulti(
            Scenario scenario,
            int perSession,
            int size,
            int batch,
            int sessions,
            long timeoutMs
    ) throws Exception {
        int total = perSession * sessions;
        AtomicInteger completed = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);

        var serverExec = Executors.newFixedThreadPool(Math.max(4, sessions));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(serverExec);
        server.createContext("/batch", ex -> handleBatch(ex, completed, total, done));
        server.createContext("/entity", ex -> handleEntity(ex, completed, total, done));
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());

        ExecutorService pool = Executors.newFixedThreadPool(sessions);
        List<ExecutorService> clientExecs = new ArrayList<>(sessions);
        try {
            long t0 = System.nanoTime();
            List<Future<?>> futures = new ArrayList<>(sessions);
            for (int s = 0; s < sessions; s++) {
                final int sessionIndex = s;
                // One HttpClient per "system" so we don't share a connection pool across
                // simulated clients (N independent machines → one receiver).
                ExecutorService clientExec = Executors.newFixedThreadPool(2);
                clientExecs.add(clientExec);
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .executor(clientExec)
                        .build();
                futures.add(pool.submit(() -> {
                    try {
                        List<byte[]> payloads = new ArrayList<>(perSession);
                        for (int i = 0; i < perSession; i++) {
                            payloads.add(BenchPayloads.payload(sessionIndex * perSession + i, size));
                        }
                        if (scenario == Scenario.LARGE_FEW) {
                            postRawEntities(client, base.resolve("/entity"), payloads);
                        } else {
                            postJsonBatches(client, base.resolve("/batch"), payloads, batch);
                        }
                    } catch (Exception e) {
                        throw new RuntimeException("rest session " + sessionIndex + " failed", e);
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get(timeoutMs + 60_000L, TimeUnit.MILLISECONDS);
            }
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(
                        "rest multi timed out: got " + completed.get() + " / " + total);
            }
            long elapsed = System.nanoTime() - t0;
            return new BenchResult(elapsed, total, (long) total * size);
        } finally {
            pool.shutdownNow();
            for (ExecutorService clientExec : clientExecs) {
                clientExec.shutdownNow();
            }
            server.stop(0);
            serverExec.shutdownNow();
        }
    }

    private static void postJsonBatches(
            HttpClient client, URI uri, List<byte[]> payloads, int batch) throws Exception {
        Base64.Encoder b64 = Base64.getEncoder();
        for (int i = 0; i < payloads.size(); i += batch) {
            int end = Math.min(payloads.size(), i + batch);
            StringBuilder json = new StringBuilder(64 + (end - i) * (payloads.get(i).length * 2 + 24));
            json.append("{\"items\":[");
            for (int j = i; j < end; j++) {
                if (j > i) {
                    json.append(',');
                }
                json.append("{\"id\":").append(j)
                        .append(",\"b\":\"")
                        .append(b64.encodeToString(payloads.get(j)))
                        .append("\"}");
            }
            json.append("]}");
            byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMinutes(2))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("REST /batch HTTP " + resp.statusCode());
            }
        }
    }

    private static void postRawEntities(HttpClient client, URI uri, List<byte[]> payloads)
            throws Exception {
        for (int i = 0; i < payloads.size(); i++) {
            byte[] body = payloads.get(i);
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMinutes(2))
                    .header("Content-Type", "application/octet-stream")
                    .header("X-Bench-Id", Integer.toString(i))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("REST /entity HTTP " + resp.statusCode());
            }
        }
    }

    private static void handleBatch(
            HttpExchange ex, AtomicInteger completed, int target, CountDownLatch done)
            throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                respond(ex, 405, "method");
                return;
            }
            String body = new String(readAll(ex.getRequestBody()), StandardCharsets.UTF_8);
            int added = countJsonItems(body);
            int n = completed.addAndGet(added);
            if (n >= target) {
                done.countDown();
            }
            respond(ex, 200, "{\"ok\":true,\"added\":" + added + "}");
        } catch (RuntimeException e) {
            respond(ex, 400, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private static void handleEntity(
            HttpExchange ex, AtomicInteger completed, int target, CountDownLatch done)
            throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "method");
            return;
        }
        readAll(ex.getRequestBody());
        int n = completed.incrementAndGet();
        if (n >= target) {
            done.countDown();
        }
        respond(ex, 200, "ok");
    }

    static int countJsonItems(String body) {
        int count = 0;
        int idx = 0;
        while ((idx = body.indexOf("\"id\":", idx)) >= 0) {
            count++;
            idx += 5;
        }
        if (count == 0 && body.contains("\"items\":[]")) {
            return 0;
        }
        if (count == 0) {
            throw new IllegalArgumentException("no items in JSON batch");
        }
        return count;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        in.transferTo(buf);
        return buf.toByteArray();
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
