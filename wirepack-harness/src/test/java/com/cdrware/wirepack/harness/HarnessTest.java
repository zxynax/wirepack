package com.cdrware.wirepack.harness;

import com.cdrware.wirepack.activate.http.SimpleActivationServer;
import com.cdrware.wirepack.api.BindAddress;
import com.cdrware.wirepack.api.SafetyLimits;
import com.cdrware.wirepack.api.SessionAcceptor;
import com.cdrware.wirepack.api.Wirepack;
import com.cdrware.wirepack.api.WirepackConfig;
import com.cdrware.wirepack.api.WirepackRuntime;
import com.cdrware.wirepack.netty.NettyUdpTransportFactory;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarnessTest {

    @Test
    void configFileIsOverriddenByFlags() throws Exception {
        Path file = Files.createTempFile("harness", ".yaml");
        Files.writeString(file, """
                mode: listen
                activateUrl: "http://127.0.0.1:9/activate" # local
                health: 127.0.0.1:8081
                keyId: 4
                """);
        HarnessConfig config = HarnessConfig.parse(new String[]{
                "--config", file.toString(),
                "--mode", "activate-then-connect",
                "--device-id", "box",
                "--peer", "10.1.1.1:9700"
        });
        assertEquals("activate-then-connect", config.mode());
        assertEquals("box", config.deviceId());
        assertEquals("http://127.0.0.1:9/activate", config.activateUrl());
        assertTrue(config.peerExplicit());
        assertEquals("10.1.1.1:9700", config.peer());
        assertEquals(4, config.keyId());
        assertEquals("127.0.0.1:8081", config.health());
        assertEquals("default", config.organization());
    }

    @Test
    void activateThenConnectNeedsAUrl() throws Exception {
        HarnessConfig config = HarnessConfig.parse(new String[]{"--mode", "activate-then-connect"});
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> Harness.start(config));
        assertTrue(ex.getMessage().contains("activateUrl"));
    }

    @Test
    void listenHealthReportsReady() throws Exception {
        HarnessConfig config = HarnessConfig.parse(new String[]{
                "--mode", "listen",
                "--bind", "127.0.0.1:0",
                "--health", "127.0.0.1:0",
                "--key", "00112233445566778899aabbccddeeff",
                "--key-id", "1"
        });
        try (Harness harness = Harness.start(config)) {
            HttpResponse<String> health = get(harness.health().baseUrl() + "/health");
            HttpResponse<String> ready = get(harness.health().baseUrl() + "/ready");
            assertEquals(200, health.statusCode());
            assertEquals("{\"status\":\"up\"}", health.body());
            assertEquals(200, ready.statusCode());
            assertTrue(harness.boundAddress().port() > 0);
        }
    }

    @Test
    void activateThenConnectDeliversPing() throws Exception {
        byte[] key = new byte[16];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i + 1);
        }
        CountDownLatch gotPing = new CountDownLatch(1);
        AtomicReference<String> body = new AtomicReference<>();
        WirepackConfig listenCfg = WirepackConfig.builder()
                .sharedKey(key)
                .keyId(7)
                .limits(SafetyLimits.defaults())
                .transport(new NettyUdpTransportFactory())
                .build();
        try (WirepackRuntime listen = Wirepack.open(listenCfg)) {
            SessionAcceptor acceptor = listen.listen(new BindAddress("127.0.0.1", 0))
                    .get(5, TimeUnit.SECONDS);
            acceptor.onAccept(session -> session.onEntity(entity -> {
                body.set(new String(entity.value(), StandardCharsets.UTF_8));
                gotPing.countDown();
            }));
            int port = acceptor.bindAddress().port();
            try (SimpleActivationServer server = new SimpleActivationServer(0, 7, key, "127.0.0.1", port)) {
                HarnessConfig config = HarnessConfig.parse(new String[]{
                        "--mode", "activate-then-connect",
                        "--activate-url", server.activateUrl(),
                        "--health", "127.0.0.1:0",
                        "--device-id", "dev",
                        "--organization", "org"
                });
                try (Harness harness = Harness.start(config)) {
                    assertTrue(gotPing.await(5, TimeUnit.SECONDS), "listener did not receive ping");
                    assertEquals("ping", body.get());
                    HttpResponse<String> ready = get(harness.health().baseUrl() + "/ready");
                    assertEquals(200, ready.statusCode());
                    assertEquals("{\"status\":\"ready\"}", ready.body());
                }
            }
        }
    }

    private static HttpResponse<String> get(String url) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
