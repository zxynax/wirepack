package com.cdrware.wirepack.api;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Reliable UDP session: send entities, receive callbacks, close.
 *
 * <p>Tree helpers ({@link #sendTree}/{@link #onTree}) encode free-form nested maps as
 * {@code KV_TREE}, auto-fragmenting large values via {@code ENTITY_FRAG}. Object helpers
 * bind POJOs/records through the same path.
 */
public interface Session extends AutoCloseable {
    int sessionId();

    PeerAddress peer();

    CompletableFuture<Void> send(List<Entity> entities);

    default CompletableFuture<Void> send(Entity entity) {
        return send(List.of(entity));
    }

    /**
     * Encode {@code root} as {@code KV_TREE} (fragmenting if needed) and send.
     */
    CompletableFuture<Void> sendTree(Map<String, ?> root);

    /**
     * Bind {@code bean} to a tree and {@link #sendTree(Map)}.
     */
    <T> CompletableFuture<Void> sendObject(T bean);

    void onEntity(Consumer<Entity> handler);

    /**
     * Invoked for each complete {@code KV_TREE} (whole entity or reassembled frags).
     * Wire {@code ENTITY_FRAG} pieces are not delivered here.
     */
    void onTree(Consumer<Map<String, Object>> handler);

    /**
     * Decode each complete tree into {@code type} and invoke {@code handler}.
     */
    <T> void onObject(Class<T> type, Consumer<T> handler);

    void onClose(Runnable handler);

    boolean isOpen();

    @Override
    void close();
}
