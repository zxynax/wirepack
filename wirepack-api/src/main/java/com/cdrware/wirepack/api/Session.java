package com.cdrware.wirepack.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Reliable UDP session: send entities, receive callbacks, close.
 */
public interface Session extends AutoCloseable {
    int sessionId();

    PeerAddress peer();

    CompletableFuture<Void> send(List<Entity> entities);

    default CompletableFuture<Void> send(Entity entity) {
        return send(List.of(entity));
    }

    void onEntity(Consumer<Entity> handler);

    void onClose(Runnable handler);

    boolean isOpen();

    @Override
    void close();
}
