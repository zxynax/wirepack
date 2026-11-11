package com.cdrware.wirepack.harness;

import com.cdrware.wirepack.activate.http.HttpsActivateDeviceProvider;
import com.cdrware.wirepack.activate.http.SimpleActivationServer;
import com.cdrware.wirepack.api.BindAddress;
import com.cdrware.wirepack.api.Entity;
import com.cdrware.wirepack.api.PeerAddress;
import com.cdrware.wirepack.api.SafetyLimits;
import com.cdrware.wirepack.api.Session;
import com.cdrware.wirepack.api.SessionAcceptor;
import com.cdrware.wirepack.api.Wirepack;
import com.cdrware.wirepack.api.WirepackConfig;
import com.cdrware.wirepack.api.WirepackRuntime;
import com.cdrware.wirepack.api.spi.ActivationRequest;
import com.cdrware.wirepack.netty.NettyUdpTransportFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runnable peer: listen, connect, or activate over HTTP(S) and then connect.
 */
public final class Harness implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(Harness.class);

    private final AtomicBoolean closed = new AtomicBoolean();
    private HealthServer health;
    private SimpleActivationServer activationServer;
    private WirepackRuntime runtime;
    private SessionAcceptor acceptor;
    private Session session;
    private BindAddress bound;

    public static Harness start(HarnessConfig config) throws Exception {
        Harness harness = new Harness();
        try {
            harness.open(config);
            return harness;
        } catch (Exception e) {
            harness.close();
            throw e;
        }
    }

    public BindAddress boundAddress() {
        return bound;
    }

    public HealthServer health() {
        return health;
    }

    public int activationServerPort() {
        return activationServer == null ? -1 : activationServer.port();
    }

    private void open(HarnessConfig config) throws Exception {
        SafetyLimits limits = SafetyLimits.defaults();
        if (config.health() != null) {
            health = HealthServer.start(config.health());
            log.info("Health on {}", health.baseUrl());
        }
        switch (config.mode()) {
            case "listen" -> listen(config, limits);
            case "connect" -> connect(config, limits, explicitPeer(config));
            case "activate-then-connect" -> activateThenConnect(config, limits);
            default -> throw new IllegalArgumentException("unknown mode: " + config.mode());
        }
    }

    private void listen(HarnessConfig config, SafetyLimits limits) throws Exception {
        byte[] key = keyBytes(config.keyHex());
        runtime = Wirepack.open(sharedKeyConfig(key, config.keyId(), limits));
        BindAddress bind = BindAddress.parse(config.bind());
        acceptor = runtime.listen(bind).get(10, TimeUnit.SECONDS);
        bound = acceptor.bindAddress();
        acceptor.onAccept(accepted -> {
            log.info("Accepted session {} from {}", accepted.sessionId(), accepted.peer());
            accepted.onEntity(e -> log.info("recv typeId={} len={}", e.typeId(), e.length()));
        });
        if (config.activationServerPort() != null) {
            String host = advertiseHost(bound.host());
            activationServer = new SimpleActivationServer(
                    config.activationServerPort(),
                    config.keyId(),
                    key,
                    host,
                    bound.port()
            );
            log.info("Activation server {}", activationServer.activateUrl());
        }
        markReady();
        log.info("Listening on {}", bound);
    }

    private void connect(HarnessConfig config, SafetyLimits limits, PeerAddress peer) throws Exception {
        byte[] key = keyBytes(config.keyHex());
        runtime = Wirepack.open(sharedKeyConfig(key, config.keyId(), limits));
        session = runtime.connect(peer).get(10, TimeUnit.SECONDS);
        session.onEntity(e -> log.info("recv typeId={} len={}", e.typeId(), e.length()));
        markReady();
        sendPing(session, peer);
    }

    private void activateThenConnect(HarnessConfig config, SafetyLimits limits) throws Exception {
        String url = config.activateUrl();
        if (config.activationServerPort() != null) {
            byte[] key = keyBytes(config.keyHex());
            PeerAddress advertised = explicitPeer(config);
            activationServer = new SimpleActivationServer(
                    config.activationServerPort(),
                    config.keyId(),
                    key,
                    advertised.host(),
                    advertised.port()
            );
            log.info("Activation server {}", activationServer.activateUrl());
            if (url == null) {
                url = activationServer.activateUrl();
            }
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException(
                    "activate-then-connect needs activateUrl or activationServer");
        }
        runtime = Wirepack.open(WirepackConfig.builder()
                .activation(new HttpsActivateDeviceProvider(url, limits))
                .activationRequest(new ActivationRequest(config.deviceId(), config.organization()))
                .limits(limits)
                .transport(new NettyUdpTransportFactory())
                .build());
        var result = runtime.config().activationResult();
        if (result == null) {
            throw new IllegalStateException("activation returned no result");
        }
        log.info("Activated keyId={} endpoints={}", result.keyId(), result.endpoints());
        PeerAddress peer = config.peerExplicit()
                ? PeerAddress.parse(config.peer())
                : firstEndpoint(result.endpoints());
        session = runtime.connect(peer).get(10, TimeUnit.SECONDS);
        session.onEntity(e -> log.info("recv typeId={} len={}", e.typeId(), e.length()));
        markReady();
        sendPing(session, peer);
    }

    private static PeerAddress explicitPeer(HarnessConfig config) {
        String peer = config.peerExplicit() ? config.peer() : "127.0.0.1:9700";
        return PeerAddress.parse(peer);
    }

    private static PeerAddress firstEndpoint(List<PeerAddress> endpoints) {
        if (endpoints == null || endpoints.isEmpty()) {
            throw new IllegalStateException("activation returned no data-plane endpoint");
        }
        return endpoints.get(0);
    }

    private static WirepackConfig sharedKeyConfig(byte[] key, int keyId, SafetyLimits limits) {
        return WirepackConfig.builder()
                .sharedKey(key)
                .keyId(keyId)
                .limits(limits)
                .transport(new NettyUdpTransportFactory())
                .build();
    }

    private static byte[] keyBytes(String keyHex) {
        if (keyHex == null || keyHex.isBlank()) {
            return new byte[16];
        }
        return HexFormat.of().parseHex(keyHex);
    }

    private static String advertiseHost(String bindHost) {
        if (bindHost == null || bindHost.isBlank() || "0.0.0.0".equals(bindHost) || "::".equals(bindHost)) {
            return "127.0.0.1";
        }
        return bindHost;
    }

    private static void sendPing(Session session, PeerAddress peer) throws Exception {
        Entity ping = new Entity(1024, "ping".getBytes(StandardCharsets.UTF_8));
        session.send(List.of(ping)).get(5, TimeUnit.SECONDS);
        log.info("Sent ping to {}", peer);
    }

    private void markReady() {
        if (health != null) {
            health.markReady();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        closeQuietly(session);
        closeQuietly(acceptor);
        closeQuietly(runtime);
        closeQuietly(activationServer);
        closeQuietly(health);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            log.debug("close failed", e);
        }
    }
}
