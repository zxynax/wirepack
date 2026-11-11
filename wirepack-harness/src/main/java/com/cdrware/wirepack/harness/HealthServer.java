package com.cdrware.wirepack.harness;

import com.cdrware.wirepack.api.BindAddress;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optional local health and readiness HTTP. Not the data plane.
 */
public final class HealthServer implements AutoCloseable {
    private final HttpServer server;
    private final AtomicBoolean ready = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private HealthServer(HttpServer server) {
        this.server = server;
    }

    public static HealthServer start(String hostPort) throws IOException {
        BindAddress bind = BindAddress.parse(hostPort);
        HttpServer server = HttpServer.create(new InetSocketAddress(bind.host(), bind.port()), 0);
        HealthServer health = new HealthServer(server);
        server.createContext("/health", health::health);
        server.createContext("/ready", health::ready);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return health;
    }

    public void markReady() {
        ready.set(true);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    private void health(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        write(exchange, 200, "{\"status\":\"up\"}");
    }

    private void ready(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        if (ready.get()) {
            write(exchange, 200, "{\"status\":\"ready\"}");
        } else {
            write(exchange, 503, "{\"status\":\"not-ready\"}");
        }
    }

    private static void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            server.stop(0);
        }
    }
}
