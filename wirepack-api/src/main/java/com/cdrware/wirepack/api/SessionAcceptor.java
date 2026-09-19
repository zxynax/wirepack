package com.cdrware.wirepack.api;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Accepts inbound sessions from listen().
 */
public interface SessionAcceptor extends AutoCloseable {
    BindAddress bindAddress();

    void onAccept(Consumer<Session> handler);

    CompletableFuture<Session> accept();

    @Override
    void close();
}
