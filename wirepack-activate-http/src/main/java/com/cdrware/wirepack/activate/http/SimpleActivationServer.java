package com.cdrware.wirepack.activate.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Tiny activation server helper for harness / local tests (JDK HttpServer).
 */
public final class SimpleActivationServer implements AutoCloseable {
    private final HttpServer server;
    private final int port;

    public SimpleActivationServer(int port, int keyId, byte[] keyMaterial, String endpointHost, int endpointPort)
            throws IOException {
        this(port, () -> responseJson(keyId, keyMaterial, endpointHost, endpointPort));
    }

    public SimpleActivationServer(int port, Supplier<String> responseBody) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.port = server.getAddress().getPort();
        server.createContext("/activate", exchange -> handle(exchange, responseBody));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    public int port() {
        return port;
    }

    public String activateUrl() {
        return "http://127.0.0.1:" + port + "/activate";
    }

    private static void handle(HttpExchange exchange, Supplier<String> responseBody) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            exchange.getRequestBody().readAllBytes();
            byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        } finally {
            exchange.close();
        }
    }

    public static String responseJson(int keyId, byte[] key, String host, int endpointPort) {
        Objects.requireNonNull(key, "key");
        return "{\"keyId\":" + keyId
                + ",\"key\":\"" + HexFormat.of().formatHex(key)
                + "\",\"endpoints\":[{\"host\":\"" + host + "\",\"port\":" + endpointPort + "}]}";
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
