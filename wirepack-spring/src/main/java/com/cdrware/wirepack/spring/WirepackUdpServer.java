package com.cdrware.wirepack.spring;

import com.cdrware.wirepack.api.BindAddress;
import com.cdrware.wirepack.api.SessionAcceptor;
import com.cdrware.wirepack.api.WirepackRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.context.SmartLifecycle;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Listens for wirepack UDP sessions and dispatches RPC to {@code @WirepackMapping} beans.
 */
public class WirepackUdpServer implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(WirepackUdpServer.class);

    private final WirepackRuntime runtime;
    private final WirepackProperties properties;
    private final ListableBeanFactory beanFactory;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile SessionAcceptor acceptor;

    public WirepackUdpServer(
            WirepackRuntime runtime,
            WirepackProperties properties,
            ListableBeanFactory beanFactory
    ) {
        this.runtime = runtime;
        this.properties = properties;
        this.beanFactory = beanFactory;
    }

    public SessionAcceptor acceptor() {
        return acceptor;
    }

    public BindAddress bindAddress() {
        SessionAcceptor a = acceptor;
        if (a == null) {
            throw new IllegalStateException("wirepack UDP server is not started");
        }
        return a.bindAddress();
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        String host = properties.getBindHost() == null || properties.getBindHost().isBlank()
                ? "0.0.0.0" : properties.getBindHost();
        try {
            acceptor = runtime.listen(new BindAddress(host, properties.getBindPort()))
                    .join();
            List<Object> beans = WirepackBeanScanner.beansWithMappings(beanFactory);
            acceptor.onAccept(session -> WirepackBeanScanner.install(session, beans));
            log.info("wirepack UDP listening on {}:{} ({} mapped bean{})",
                    acceptor.bindAddress().host(),
                    acceptor.bindAddress().port(),
                    beans.size(),
                    beans.size() == 1 ? "" : "s");
        } catch (Exception e) {
            running.set(false);
            throw new IllegalStateException("wirepack UDP listen failed", e);
        }
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        SessionAcceptor a = acceptor;
        acceptor = null;
        if (a != null) {
            a.close();
        }
        runtime.close();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }
}
