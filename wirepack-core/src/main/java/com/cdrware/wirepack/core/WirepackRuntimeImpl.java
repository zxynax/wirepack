package com.cdrware.wirepack.core;

import com.cdrware.wirepack.api.BindAddress;
import com.cdrware.wirepack.api.PeerAddress;
import com.cdrware.wirepack.api.Session;
import com.cdrware.wirepack.api.SessionAcceptor;
import com.cdrware.wirepack.api.WirepackBootstrap;
import com.cdrware.wirepack.api.WirepackConfig;
import com.cdrware.wirepack.api.WirepackRuntime;
import com.cdrware.wirepack.api.spi.ActivationException;
import com.cdrware.wirepack.api.spi.ActivationProvider;
import com.cdrware.wirepack.api.spi.ActivationRequest;
import com.cdrware.wirepack.api.spi.ActivationResult;
import com.cdrware.wirepack.api.spi.TransportFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Default {@link WirepackRuntime}: shared-key activation path for v0; null ActivationProvider OK.
 */
public final class WirepackRuntimeImpl implements WirepackRuntime {
    private static final Logger log = LoggerFactory.getLogger(WirepackRuntimeImpl.class);

    static {
        WirepackBootstrap.install(WirepackRuntimeImpl::open);
    }

    private final WirepackConfig config;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicInteger sessionIds = new AtomicInteger(1);
    private volatile TransportFactory.Transport transport;

    private WirepackRuntimeImpl(WirepackConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public static WirepackRuntime open(WirepackConfig config) {
        WirepackConfig resolved = resolveActivation(config);
        return new WirepackRuntimeImpl(resolved);
    }

    /**
     * Shared-key only for v0: if ActivationProvider present, call it; else use sharedKey as-is.
     * Null ActivationProvider is OK.
     */
    static WirepackConfig resolveActivation(WirepackConfig config) {
        ActivationProvider provider = config.activationProvider();
        if (provider == null) {
            if (config.sharedKeyRaw() == null && config.activationResult() == null) {
                log.debug("No shared key or activation; proceeding with keyId={}", config.keyId());
            }
            return config;
        }
        ActivationRequest req = config.activationRequest();
        if (req == null) {
            req = new ActivationRequest("device", "default");
        }
        try {
            ActivationResult result = provider.activate(req);
            return WirepackConfig.builder()
                    .sharedKey(result.keyMaterialRaw())
                    .keyId(result.keyId())
                    .activationResult(result)
                    .activation(provider)
                    .activationRequest(req)
                    .transport(config.transportFactory())
                    .limits(config.limits())
                    .crypto(config.crypto())
                    .preferAesGcm(config.preferAesGcm())
                    .windowSize(config.windowSize())
                    .build();
        } catch (ActivationException e) {
            throw new IllegalStateException("activation failed", e);
        }
    }

    @Override
    public WirepackConfig config() {
        return config;
    }

    private TransportFactory.Transport transport() {
        TransportFactory.Transport t = transport;
        if (t != null) {
            return t;
        }
        synchronized (this) {
            if (transport == null) {
                TransportFactory factory = config.transportFactory();
                if (factory == null) {
                    throw new IllegalStateException(
                            "No TransportFactory configured. Add wirepack-netty and set "
                                    + "WirepackConfig.builder().transport(new NettyUdpTransportFactory())");
                }
                transport = factory.create(config);
            }
            return transport;
        }
    }

    @Override
    public CompletableFuture<Session> connect(PeerAddress peer) {
        ensureOpen();
        return transport().connect(peer);
    }

    @Override
    public CompletableFuture<SessionAcceptor> listen(BindAddress bind) {
        ensureOpen();
        TransportFactory.Transport t = transport();
        CopyOnWriteArrayList<Consumer<Session>> handlers = new CopyOnWriteArrayList<>();
        CompletableFuture<Session> firstSession = new CompletableFuture<>();

        return t.listen(bind, session -> {
            firstSession.complete(session);
            for (Consumer<Session> h : handlers) {
                try {
                    h.accept(session);
                } catch (Exception e) {
                    log.warn("accept handler error", e);
                }
            }
        }).thenApply(ignored -> {
            BindAddress actual = t.localAddress() != null ? t.localAddress() : bind;
            return new SessionAcceptor() {
                @Override
                public BindAddress bindAddress() {
                    return actual;
                }

                @Override
                public void onAccept(Consumer<Session> handler) {
                    handlers.add(handler);
                }

                @Override
                public CompletableFuture<Session> accept() {
                    return firstSession;
                }

                @Override
                public void close() {
                    // closed with runtime
                }
            };
        });
    }

    public int nextSessionId() {
        return sessionIds.getAndIncrement();
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("runtime closed");
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        TransportFactory.Transport t = transport;
        if (t != null) {
            t.close();
        }
    }

    /** ServiceLoader entry. */
    public static final class Opener implements WirepackBootstrap.WirepackOpener {
        @Override
        public WirepackRuntime open(WirepackConfig config) {
            return WirepackRuntimeImpl.open(config);
        }
    }
}
